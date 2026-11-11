-- =============================================================
-- 校园问卷信息调查管理系统 —— 01 认证与 RBAC 建表脚本
-- 数据库：G14(1)  字符集：utf8mb4
-- 说明：本脚本只建「认证 + 用户 + 权限 + 日志」相关表，
--       问卷/答卷/模板等表随后续模块的 02_*.sql 补充。
-- 特性：MyBatis-Plus 逻辑删除（deleted 0/1）+ 审计字段自动填充
-- =============================================================

CREATE DATABASE IF NOT EXISTS `G14(1)`
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_general_ci;

USE `G14(1)`;

-- -------------------------------------------------------------
-- 1. 用户表
-- -------------------------------------------------------------
DROP TABLE IF EXISTS `sys_user`;
CREATE TABLE `sys_user` (
  `id`                   BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `username`             VARCHAR(64)     NOT NULL                COMMENT '登录账号：学号/工号/管理员账号',
  `password_hash`        VARCHAR(100)    NOT NULL                COMMENT 'BCrypt 密码哈希（$2a$10$...）',
  `real_name`            VARCHAR(64)     NOT NULL                COMMENT '姓名',
  `status`               TINYINT         NOT NULL DEFAULT 1      COMMENT '状态：1=正常 0=禁用',
  `audit_status`         TINYINT         NOT NULL DEFAULT 1      COMMENT '审核状态：0=待审核 1=已通过 2=已驳回（ADMIN 导入默认已通过）',
  `must_change_password` TINYINT         NOT NULL DEFAULT 0      COMMENT '是否强制下次登录改密：1=是 0=否',
  `phone`                VARCHAR(20)              DEFAULT NULL   COMMENT '手机号',
  `email`                VARCHAR(128)             DEFAULT NULL   COMMENT '邮箱',
  `department`           VARCHAR(128)             DEFAULT NULL   COMMENT '院系/班级',
  `token_version`        INT             NOT NULL DEFAULT 0      COMMENT '令牌版本：改密/重置/禁用时+1，使旧 JWT 立即失效',
  `login_fail_count`     INT             NOT NULL DEFAULT 0      COMMENT '连续登录失败次数',
  `locked_until`         DATETIME                 DEFAULT NULL   COMMENT '锁定截止时间，NULL=未锁定',
  `last_login_time`      DATETIME                 DEFAULT NULL   COMMENT '最近一次登录成功时间',
  `audit_remark`         VARCHAR(255)             DEFAULT NULL   COMMENT '审核意见（驳回原因）',
  `remark`               VARCHAR(255)             DEFAULT NULL   COMMENT '备注',
  `deleted`              TINYINT         NOT NULL DEFAULT 0      COMMENT '逻辑删除：0=未删 1=已删',
  `create_by`            BIGINT UNSIGNED          DEFAULT NULL   COMMENT '创建人 ID',
  `create_time`          DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_by`            BIGINT UNSIGNED          DEFAULT NULL   COMMENT '更新人 ID',
  `update_time`          DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_username` (`username`),
  KEY `idx_status` (`status`, `deleted`),
  KEY `idx_audit` (`audit_status`, `deleted`),
  KEY `idx_phone` (`phone`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='用户表';

-- -------------------------------------------------------------
-- 2. 角色表（固定三值，不新增角色）
-- -------------------------------------------------------------
DROP TABLE IF EXISTS `sys_role`;
CREATE TABLE `sys_role` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `code`        VARCHAR(32)     NOT NULL                COMMENT '角色码：ROLE_ADMIN/ROLE_TEACHER/ROLE_STUDENT',
  `name`        VARCHAR(32)     NOT NULL                COMMENT '角色名称',
  `description` VARCHAR(255)             DEFAULT NULL   COMMENT '说明',
  `builtin`     TINYINT         NOT NULL DEFAULT 1      COMMENT '是否内置角色：1=是，不可删除',
  `create_time` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_code` (`code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='角色表';

-- -------------------------------------------------------------
-- 3. 权限表（权限码字典）
-- -------------------------------------------------------------
DROP TABLE IF EXISTS `sys_permission`;
CREATE TABLE `sys_permission` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `code`        VARCHAR(64)     NOT NULL                COMMENT '权限码：模块:动作[:范围]',
  `name`        VARCHAR(64)     NOT NULL                COMMENT '权限名称',
  `module`      VARCHAR(32)     NOT NULL                COMMENT '所属模块',
  `description` VARCHAR(255)             DEFAULT NULL   COMMENT '说明',
  `create_time` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_code` (`code`),
  KEY `idx_module` (`module`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='权限字典表';

-- -------------------------------------------------------------
-- 4. 用户-角色（本期一人一角色，表结构预留多角色）
-- -------------------------------------------------------------
DROP TABLE IF EXISTS `sys_user_role`;
CREATE TABLE `sys_user_role` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id`     BIGINT UNSIGNED NOT NULL                COMMENT '用户 ID',
  `role_id`     BIGINT UNSIGNED NOT NULL                COMMENT '角色 ID',
  `create_by`   BIGINT UNSIGNED          DEFAULT NULL   COMMENT '操作人 ID',
  `create_time` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_role` (`user_id`, `role_id`),
  KEY `idx_role` (`role_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='用户角色关联表';

-- -------------------------------------------------------------
-- 5. 角色-权限
-- -------------------------------------------------------------
DROP TABLE IF EXISTS `sys_role_permission`;
CREATE TABLE `sys_role_permission` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `role_id`     BIGINT UNSIGNED NOT NULL                COMMENT '角色 ID',
  `perm_id`     BIGINT UNSIGNED NOT NULL                COMMENT '权限 ID',
  `create_time` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_role_perm` (`role_id`, `perm_id`),
  KEY `idx_perm` (`perm_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='角色权限关联表';

-- -------------------------------------------------------------
-- 6. 用户级追加权限（学生干部发卷走这里，不新增角色）
--    只做「加权限」，不做减权限
-- -------------------------------------------------------------
DROP TABLE IF EXISTS `sys_user_permission`;
CREATE TABLE `sys_user_permission` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id`     BIGINT UNSIGNED NOT NULL                COMMENT '用户 ID',
  `perm_id`     BIGINT UNSIGNED NOT NULL                COMMENT '权限 ID',
  `grant_by`    BIGINT UNSIGNED          DEFAULT NULL   COMMENT '授予人 ID',
  `grant_time`  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '授予时间',
  `remark`      VARCHAR(255)             DEFAULT NULL   COMMENT '授予原因',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_perm` (`user_id`, `perm_id`),
  KEY `idx_perm` (`perm_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='用户追加权限表';

-- -------------------------------------------------------------
-- 7. 登录日志（只增不改，不做逻辑删除）
-- -------------------------------------------------------------
DROP TABLE IF EXISTS `sys_login_log`;
CREATE TABLE `sys_login_log` (
  `id`         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id`    BIGINT UNSIGNED          DEFAULT NULL   COMMENT '用户 ID，账号不存在时为 NULL',
  `username`   VARCHAR(64)     NOT NULL                COMMENT '本次登录输入的账号',
  `result`     TINYINT         NOT NULL                COMMENT '结果：1=成功 0=失败',
  `reason`     VARCHAR(64)              DEFAULT NULL   COMMENT '失败原因',
  `ip`         VARCHAR(64)              DEFAULT NULL   COMMENT '客户端 IP',
  `user_agent` VARCHAR(255)             DEFAULT NULL   COMMENT 'User-Agent',
  `create_time` DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '发生时间',
  PRIMARY KEY (`id`),
  KEY `idx_username_time` (`username`, `create_time`),
  KEY `idx_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='登录日志表';

-- -------------------------------------------------------------
-- 8. 操作日志（只增不改）
-- -------------------------------------------------------------
DROP TABLE IF EXISTS `sys_operation_log`;
CREATE TABLE `sys_operation_log` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id`     BIGINT UNSIGNED          DEFAULT NULL   COMMENT '操作人 ID',
  `username`    VARCHAR(64)              DEFAULT NULL   COMMENT '操作人账号（冗余，便于追溯）',
  `module`      VARCHAR(32)     NOT NULL                COMMENT '模块',
  `action`      VARCHAR(64)     NOT NULL                COMMENT '动作编码',
  `target_type` VARCHAR(32)              DEFAULT NULL   COMMENT '对象类型',
  `target_id`   BIGINT UNSIGNED          DEFAULT NULL   COMMENT '对象 ID',
  `detail`      VARCHAR(1000)            DEFAULT NULL   COMMENT '详情（脱敏后）',
  `result`      TINYINT         NOT NULL DEFAULT 1      COMMENT '结果：1=成功 0=失败',
  `ip`          VARCHAR(64)              DEFAULT NULL   COMMENT '客户端 IP',
  `create_time` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '发生时间',
  PRIMARY KEY (`id`),
  KEY `idx_user_time` (`user_id`, `create_time`),
  KEY `idx_module_time` (`module`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='操作日志表';

-- =============================================================
-- 初始化数据
-- =============================================================

-- 角色（三值固定）
INSERT INTO `sys_role` (`code`, `name`, `description`, `builtin`) VALUES
  ('ROLE_ADMIN',   '系统管理员', '用户/角色管理、日志、模板审核、系统配置、全量数据管理', 1),
  ('ROLE_TEACHER', '教师',      '创建/编辑/发布自己的问卷，查看自己问卷的答卷、统计、导出，共享模板', 1),
  ('ROLE_STUDENT', '学生',      '填写问卷、保存草稿、断点续答、查看自己的填写记录、浏览和评分模板', 1);

-- 权限字典
INSERT INTO `sys_permission` (`code`, `name`, `module`, `description`) VALUES
  -- 认证
  ('auth:login',                 '登录',             'auth',     '账号密码登录'),
  ('auth:logout',                '登出',             'auth',     '退出登录'),
  ('auth:password:self',         '修改自己的密码',    'auth',     '校验原密码后修改'),
  ('auth:profile:self',          '查看/编辑自己资料', 'auth',     '个人中心'),
  -- 用户管理
  ('user:list',                  '用户列表',          'user',     '查询用户列表'),
  ('user:create',                '新增用户',          'user',     '单个新增用户'),
  ('user:update',                '编辑用户',          'user',     '修改用户基本信息'),
  ('user:delete',                '删除用户',          'user',     '逻辑删除用户'),
  ('user:status',                '启用/禁用用户',      'user',     '切换用户状态'),
  ('user:reset-password',        '重置他人密码',       'user',     '重置为默认密码'),
  ('user:import',                '批量导入用户',       'user',     'Excel 导入'),
  ('user:audit',                 '审核注册申请',       'user',     '通过/驳回自助注册'),
  ('user:grant',                 '分配角色与权限',     'user',     '变更角色、追加用户权限'),
  ('role:list',                  '角色列表',          'user',     '查询角色'),
  ('role:manage',                '角色权限维护',       'user',     '维护角色与角色权限'),
  -- 问卷
  ('survey:create',              '创建问卷',          'survey',   '成为"问卷发布者"的标志权限'),
  ('survey:update:self',         '编辑自己的问卷',     'survey',   ''),
  ('survey:update:all',          '编辑任意问卷',       'survey',   'ADMIN 专用'),
  ('survey:delete:self',         '删除自己的问卷',     'survey',   ''),
  ('survey:delete:all',          '删除任意问卷',       'survey',   'ADMIN 专用'),
  ('survey:publish:self',        '发布自己的问卷',     'survey',   '发布/暂停/结束'),
  ('survey:publish:all',         '管理任意问卷发布',   'survey',   'ADMIN 强制下架'),
  ('survey:list:self',           '我的问卷列表',       'survey',   ''),
  ('survey:list:all',            '全量问卷列表',       'survey',   ''),
  ('survey:preview',             '预览问卷',          'survey',   ''),
  ('survey:copy',                '复制问卷',          'survey',   ''),
  -- 填写与答卷
  ('survey:fill',                '填写问卷',          'answer',   ''),
  ('draft:save:self',            '保存/读取草稿',      'answer',   '断点续答'),
  ('answer:read:self',           '查看自己的填写记录',  'answer',   ''),
  ('answer:read:survey:self',    '查看自己问卷的答卷',  'answer',   ''),
  ('answer:read:survey:all',     '查看任意问卷的答卷',  'answer',   ''),
  ('answer:invalidate',          '标记答卷无效',       'answer',   ''),
  ('answer:delete',              '删除答卷',          'answer',   ''),
  -- 统计与导出
  ('stat:read:self',             '查看自己问卷统计',    'stat',     ''),
  ('stat:read:all',              '查看任意问卷统计',    'stat',     ''),
  ('export:self',                '导出自己问卷数据',    'export',   ''),
  ('export:all',                 '导出任意问卷数据',    'export',   ''),
  -- 模板
  ('template:browse',            '浏览模板',          'template', ''),
  ('template:share',             '共享问卷为模板',      'template', ''),
  ('template:offline',           '下架自己的模板',      'template', ''),
  ('template:audit',             '审核模板',          'template', ''),
  ('template:use',               '使用模板建卷',       'template', ''),
  ('template:rate',              '评分模板',          'template', '仅学生'),
  -- 日志
  ('log:login:read',             '查看登录日志',       'log',      ''),
  ('log:operation:read',         '查看操作日志',       'log',      ''),
  ('log:export',                 '导出日志',          'log',      ''),
  -- 系统
  ('config:read',                '读取系统配置',       'config',   ''),
  ('config:manage',              '修改系统配置',       'config',   ''),
  ('dashboard:read',             '管理端数据看板',     'system',   ''),
  ('survey:manage:all',          '全量数据管理',       'system',   '聚合权限，ADMIN 专用');

-- -------------------------------------------------------------
-- 角色 -> 权限
-- -------------------------------------------------------------

-- ADMIN：全部权限
INSERT INTO `sys_role_permission` (`role_id`, `perm_id`)
SELECT r.id, p.id FROM `sys_role` r CROSS JOIN `sys_permission` p WHERE r.code = 'ROLE_ADMIN';

-- TEACHER：自己的问卷全流程 + 模板共享 + 只读配置
INSERT INTO `sys_role_permission` (`role_id`, `perm_id`)
SELECT r.id, p.id FROM `sys_role` r CROSS JOIN `sys_permission` p
WHERE r.code = 'ROLE_TEACHER'
  AND p.code IN (
    'auth:login','auth:logout','auth:password:self','auth:profile:self',
    'survey:create','survey:update:self','survey:delete:self','survey:publish:self',
    'survey:list:self','survey:preview','survey:copy',
    'survey:fill','draft:save:self',
    'answer:read:self','answer:read:survey:self','answer:invalidate',
    'stat:read:self','export:self',
    'template:browse','template:share','template:offline','template:use',
    'config:read'
  );

-- STUDENT：填写 + 评分模板
INSERT INTO `sys_role_permission` (`role_id`, `perm_id`)
SELECT r.id, p.id FROM `sys_role` r CROSS JOIN `sys_permission` p
WHERE r.code = 'ROLE_STUDENT'
  AND p.code IN (
    'auth:login','auth:logout','auth:password:self','auth:profile:self',
    'survey:fill','draft:save:self','answer:read:self',
    'template:browse','template:rate',
    'config:read'
  );

-- =============================================================
-- 演示账号（BCrypt，cost=10）
--   admin    / Admin@123      ROLE_ADMIN
--   teacher  / Teacher@123    ROLE_TEACHER
--   student  / Student@123    ROLE_STUDENT
-- 生产部署请立即修改，或改由 ADMIN 导入真实花名册。
-- =============================================================
INSERT INTO `sys_user`
  (`username`, `password_hash`, `real_name`, `status`, `audit_status`, `must_change_password`, `department`, `remark`)
VALUES
  ('admin',   '$2a$10$ycGRbaFEHWw7j0ISRRgIDO8EJR5dueAFCMqjChkOqVXWJgDRQyhjK', '系统管理员', 1, 1, 0, '信息中心',   '内置管理员'),
  ('teacher', '$2a$10$/lFQiizTMMwFd6./IkXGPOhgFZZiq52kqRUA6xKvBJnEcbcn30fYK', '张老师',     1, 1, 0, '计算机学院', '演示教师'),
  ('student', '$2a$10$WzofiOa8hBdqPwNx70vlMOhw26XdRMiuPqqrS0Kzof4BsJcrx2WoW', '李同学',     1, 1, 0, '计算机学院2023级1班', '演示学生');

-- 绑定角色
INSERT INTO `sys_user_role` (`user_id`, `role_id`, `create_by`)
SELECT u.id, r.id, 1
FROM `sys_user` u
JOIN `sys_role` r ON r.code = 'ROLE_ADMIN'
WHERE u.username = 'admin';

INSERT INTO `sys_user_role` (`user_id`, `role_id`, `create_by`)
SELECT u.id, r.id, 1
FROM `sys_user` u
JOIN `sys_role` r ON r.code = 'ROLE_TEACHER'
WHERE u.username = 'teacher';

INSERT INTO `sys_user_role` (`user_id`, `role_id`, `create_by`)
SELECT u.id, r.id, 1
FROM `sys_user` u
JOIN `sys_role` r ON r.code = 'ROLE_STUDENT'
WHERE u.username = 'student';

