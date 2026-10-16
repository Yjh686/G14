-- =====================================================================
--  班级问卷系统 —— 用户认证与角色权限模块  数据库脚本
--  DB: G14     Charset: utf8mb4     Engine: InnoDB
--
--  表命名与列名与前端工具链严格对齐：
--    tools/unlock-accounts.mjs 直连 sys_user.login_fail_count / locked_until
--    tools/seed-demo.sql       写入 class_info / class_student / questionnaire
--
--  执行：mysql -uroot -p123456 -h127.0.0.1 --default-character-set=utf8mb4 < schema.sql
-- =====================================================================

CREATE DATABASE IF NOT EXISTS `G14`
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_general_ci;

USE `G14`;

-- ---------------------------------------------------------------------
-- 表 1：sys_user —— 用户表（学生 / 老师 / 管理员统一存储）
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS `sys_user`;
CREATE TABLE `sys_user`
(
    `id`                   BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    `account`              VARCHAR(64)     NOT NULL COMMENT '登录账号：学生学号 / 老师工号 / 管理员账号',
    `password_hash`        VARCHAR(100)    NOT NULL COMMENT 'bcrypt 密码哈希（$2a$10$...，60 字符）',
    `real_name`            VARCHAR(64)     NOT NULL COMMENT '姓名',
    `role`                 VARCHAR(16)     NOT NULL COMMENT '角色：STUDENT / TEACHER / ADMIN',
    `status`               TINYINT         NOT NULL DEFAULT 1 COMMENT '状态：1=正常 0=禁用',
    `must_change_password` TINYINT         NOT NULL DEFAULT 1 COMMENT '是否需强制改密：1=是 0=否',
    `phone`                VARCHAR(20)              DEFAULT NULL COMMENT '手机号（老师可凭手机号登录）',
    `token_version`        INT             NOT NULL DEFAULT 0 COMMENT '令牌版本号：改密/重置/禁用时 +1，使旧令牌立即失效',
    `login_fail_count`     INT             NOT NULL DEFAULT 0 COMMENT '连续登录失败次数',
    `locked_until`         DATETIME                 DEFAULT NULL COMMENT '锁定截止时间，NULL=未锁定',
    `last_login_time`      DATETIME                 DEFAULT NULL COMMENT '最近一次登录成功时间',
    `created_at`           DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at`           DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_account` (`account`),
    UNIQUE KEY `uk_phone` (`phone`),
    KEY `idx_role` (`role`),
    KEY `idx_role_status` (`role`, `status`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='用户表';

-- ---------------------------------------------------------------------
-- 表 2：sys_login_log —— 登录审计日志
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS `sys_login_log`;
CREATE TABLE `sys_login_log`
(
    `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '日志ID',
    `user_id`     BIGINT UNSIGNED          DEFAULT NULL COMMENT '用户ID，账号不存在时为 NULL',
    `account`     VARCHAR(64)     NOT NULL COMMENT '本次登录输入的账号',
    `login_type`  VARCHAR(16)     NOT NULL DEFAULT 'ACCOUNT' COMMENT '登录方式：ACCOUNT=账号 PHONE=手机号',
    `result`      TINYINT         NOT NULL COMMENT '结果：1=成功 0=失败',
    `reason`      VARCHAR(64)              DEFAULT NULL COMMENT '失败原因',
    `ip`          VARCHAR(64)              DEFAULT NULL COMMENT '客户端 IP',
    `user_agent`  VARCHAR(255)             DEFAULT NULL COMMENT 'User-Agent',
    `created_at`  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '发生时间',
    PRIMARY KEY (`id`),
    KEY `idx_account_time` (`account`, `created_at`),
    KEY `idx_user_time` (`user_id`, `created_at`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='登录审计日志';

-- ---------------------------------------------------------------------
-- 表 3：class_info —— 班级表
--   班级/问卷模块本体尚未交付，本表由权限演示端点（/api/permission-demo/*）
--   用于验证「数据级越权拦截」，字段与 tools/seed-demo.sql 保持一致。
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS `class_info`;
CREATE TABLE `class_info`
(
    `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '班级ID',
    `name`        VARCHAR(64)     NOT NULL COMMENT '班级名称',
    `invite_code` VARCHAR(16)     NOT NULL COMMENT '学生加入用邀请码',
    `teacher_id`  BIGINT UNSIGNED NOT NULL COMMENT '创建老师 sys_user.id',
    `created_at`  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_invite_code` (`invite_code`),
    KEY `idx_teacher` (`teacher_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='班级表';

-- ---------------------------------------------------------------------
-- 表 4：class_student —— 班级成员表（学生加入的班级）
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS `class_student`;
CREATE TABLE `class_student`
(
    `id`         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `class_id`   BIGINT UNSIGNED NOT NULL,
    `student_id` BIGINT UNSIGNED NOT NULL COMMENT '学生 sys_user.id',
    `created_at` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_class_student` (`class_id`, `student_id`),
    KEY `idx_student` (`student_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='班级成员表';

-- ---------------------------------------------------------------------
-- 表 5：questionnaire —— 问卷表
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS `questionnaire`;
CREATE TABLE `questionnaire`
(
    `id`         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `class_id`   BIGINT UNSIGNED NOT NULL COMMENT '发布到的班级',
    `title`      VARCHAR(128)    NOT NULL COMMENT '问卷标题',
    `creator_id` BIGINT UNSIGNED NOT NULL COMMENT '创建老师 sys_user.id',
    `status`     TINYINT         NOT NULL DEFAULT 0 COMMENT '状态：1=已发布 0=草稿（学生不可见）',
    `created_at` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_class` (`class_id`),
    KEY `idx_creator` (`creator_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='问卷表';

-- ---------------------------------------------------------------------
-- 引导管理员账号由应用启动时的 AdminInitializer 自动创建
-- （账号 admin / 初始密码 Admin@123），避免在脚本中硬编码 bcrypt 哈希。
-- 演示账号请用管理员 API 开通：node tools/seed-demo.mjs
-- ---------------------------------------------------------------------
