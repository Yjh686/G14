-- =============================================================
-- 06 密码找回（密保问题）
-- 执行：mysql -u root -p --default-character-set=utf8mb4 "G14(1)" -e "source 06_schema_recovery.sql"
-- =============================================================

-- 必须自带 USE：本脚本也被挂载进 MySQL 容器的 /docker-entrypoint-initdb.d/，
-- 而 compose 没有设置 MYSQL_DATABASE，官方 entrypoint 调 mysql 客户端时不带
-- 默认库，缺少这一行会直接 ERROR 1046 (3D000) No database selected，
-- 容器初始化失败、整站起不来。（05 踩过这个坑）
USE `G14(1)`;

-- -------------------------------------------------------------
-- 为什么单独一张表，而不是往 sys_user 加三列
--
-- 1. sys_user 是高频查询表，找回密码一年也用不了几次。把
--    question1/answer_hash1/question2/answer_hash2 这些列摊在主表上，
--    每次登录、每次鉴权查用户都要多带几列无关数据。
-- 2. 一个人可能想换密保问题，改密保和改密码是两件事，分开好写日志。
-- 3. 这张表天然是「一个用户一行」，用 user_id 做唯一键比塞进主表更直白。
-- -------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `sys_user_recovery` (
  `id`             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id`        BIGINT UNSIGNED NOT NULL                COMMENT '用户 ID，一人一行',
  -- 问题文本直接存，不做「预设问题字典」：字典表会让老师/学生无法问出
  -- 自己想问的（比如「我的宿舍号是多少」），而这类答案恰恰最难被同班同学猜到。
  `question1`      VARCHAR(120)    NOT NULL                COMMENT '密保问题 1',
  -- 答案用 BCrypt 存，和密码同等对待。明文答案的问题在于：很多人会用
  -- 学号、生日、宿舍号当答案，而这些信息在班级群里是公开的，
  -- 一旦库被拖走，等于所有账号都有了第二把钥匙。
  `answer_hash1`   VARCHAR(100)    NOT NULL                COMMENT '答案 1 的 BCrypt 哈希',
  `question2`      VARCHAR(120)    NOT NULL                COMMENT '密保问题 2',
  `answer_hash2`   VARCHAR(100)    NOT NULL                COMMENT '答案 2 的 BCrypt 哈希',
  -- 失败计数与锁定放在这张表而不是 sys_user：找回密码的失败不该把
  -- 正常登录也一起锁掉。反过来也一样——有人拿别人的学号乱试密保，
  -- 不应该导致那个人上不了课。
  `fail_count`     INT             NOT NULL DEFAULT 0      COMMENT '连续验证失败次数',
  `locked_until`   DATETIME                 DEFAULT NULL   COMMENT '验证锁定截止时间，NULL=未锁定',
  `last_verify_time` DATETIME               DEFAULT NULL   COMMENT '最近一次验证成功时间',
  `create_time`    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time`    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  -- 一人一行：重复设置走「先查再更新」，并发下由这个唯一键兜底。
  -- 与 answer_sheet 的 uniq_user 生成列不同，这里没有「要不要占槽」的语义，
  -- 直接 (user_id) 唯一即可。
  UNIQUE KEY `uk_user` (`user_id`),
  KEY `idx_locked` (`locked_until`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='密保问题（FR-AUTH 密码找回）';

-- -------------------------------------------------------------
-- 找回密码的尝试记录
--
-- 为什么单独记表而不只依赖 fail_count：
-- 这是一条**没有前置登录态**的通道，也是最容易被拿来做账号枚举的地方。
-- 光有计数看不到「谁在什么时候从哪个 IP 试了哪些账号」，出了事查不动。
-- 只增不改，与 sys_login_log / sys_operation_log 同样的口径。
-- -------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `sys_recovery_log` (
  `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id`       BIGINT UNSIGNED          DEFAULT NULL   COMMENT '命中的用户 ID，账号不存在时为空',
  `username`      VARCHAR(64)     NOT NULL                COMMENT '请求里的账号原文',
  `step`          VARCHAR(16)     NOT NULL                COMMENT '步骤：LOOKUP=查询密保问题 / VERIFY=校验答案 / RESET=重设密码',
  `result`        TINYINT         NOT NULL                COMMENT '结果：1=成功 0=失败',
  `reason`        VARCHAR(255)             DEFAULT NULL   COMMENT '失败原因（成功时为空）',
  `ip`            VARCHAR(64)              DEFAULT NULL   COMMENT '来源 IP',
  `create_time`   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '发生时间',
  PRIMARY KEY (`id`),
  KEY `idx_username_time` (`username`, `create_time`),
  KEY `idx_user_time` (`user_id`, `create_time`),
  KEY `idx_result_time` (`result`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='密保找回尝试记录（只增不改）';

-- 找回通道的开关与闸门，做成可配置项（管理员在系统配置页可改，改完即时生效）
INSERT INTO `sys_config`
  (`config_key`, `config_value`, `config_type`, `group_name`, `description`, `is_public`, `builtin`, `create_by`)
VALUES
  ('security.recovery.enabled', '1', 'BOOLEAN', 'SECURITY',
   '是否开启密保问题自助找回密码：1=开启 0=关闭（关闭后全部走管理员重置）', 1, 1, 1),
  ('security.recovery.max-fail', '5', 'NUMBER', 'SECURITY',
   '密保答案连续答错几次后锁定，范围 3~10', 1, 1, 1),
  ('security.recovery.lock-minutes', '30', 'NUMBER', 'SECURITY',
   '密保验证锁定时长（分钟），范围 5~1440', 1, 1, 1)
ON DUPLICATE KEY UPDATE `config_key` = `config_key`;

-- 验证
SELECT 'sys_user_recovery' AS `table`, COUNT(*) AS `rows` FROM `sys_user_recovery`
UNION ALL SELECT 'sys_recovery_log', COUNT(*) FROM `sys_recovery_log`
UNION ALL SELECT 'sys_config(recovery)', COUNT(*) FROM `sys_config` WHERE `config_key` LIKE 'security.recovery.%';
