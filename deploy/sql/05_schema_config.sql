-- =============================================================
-- 05 系统配置（FR-CFG-01 / FR-CFG-02 / FR-CFG-03）
-- 执行：mysql -u root -p --default-character-set=utf8mb4 "G14(1)" -e "source 05_schema_config.sql"
-- =============================================================

CREATE TABLE IF NOT EXISTS sys_config (
    id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    config_key   VARCHAR(64)     NOT NULL COMMENT '配置键，如 site.name',
    config_value TEXT            NULL     COMMENT '配置值，统一按字符串存，读取时按 config_type 解释',
    config_type  VARCHAR(16)     NOT NULL DEFAULT 'STRING'
        COMMENT 'STRING / TEXT / NUMBER / BOOLEAN / PASSWORD（仅影响前端控件与校验，存储一律是字符串）',
    group_name   VARCHAR(32)     NOT NULL DEFAULT 'SITE' COMMENT 'SITE / SECURITY / OTHER',
    description  VARCHAR(255)    NULL     COMMENT '给管理员看的中文说明',
    is_public    TINYINT         NOT NULL DEFAULT 0 COMMENT '1=未登录也能读（站点名、公告、密码规则）',
    builtin      TINYINT         NOT NULL DEFAULT 0 COMMENT '1=系统内置，可改值但不可删除',
    create_by    BIGINT UNSIGNED NULL,
    create_time  DATETIME        NULL,
    update_by    BIGINT UNSIGNED NULL,
    update_time  DATETIME        NULL,
    deleted      TINYINT         NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    -- 唯一键只挂 config_key（不带 deleted）。
    -- 曾考虑写成 (config_key, deleted) 让「删了还能重建同名键」，但那样同一键
    -- 第二次被删除时会与上一行的 (key, 1) 相撞 —— 逻辑删除的行不会消失。
    -- 现在的做法：新建时若键已存在但 deleted=1，就把那一行复活并覆盖值（见 ConfigService）。
    UNIQUE KEY uk_key (config_key),
    KEY idx_group (group_name, deleted)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT '系统配置键值对';

-- -------------------------------------------------------------
-- 内置配置项
-- 全部内置值必须与代码里的兜底默认值一致，否则「数据库里有值」和
-- 「数据库里没这行」会给出两套行为，排查时极难对上。
-- -------------------------------------------------------------
INSERT INTO sys_config (config_key, config_value, config_type, group_name, description, is_public, builtin, create_by, create_time, update_by, update_time, deleted)
VALUES
-- 站点信息（FR-CFG-01）
('site.name',         '校园问卷系统', 'STRING', 'SITE',     '站点名称，显示在登录页、浏览器标题与侧边栏',           1, 1, 1, NOW(), 1, NOW(), 0),
('site.logo',         '',             'STRING', 'SITE',     'Logo 图片地址，留空则只显示文字站点名',               1, 1, 1, NOW(), 1, NOW(), 0),
('site.announcement', '',             'TEXT',   'SITE',     '公告文案，登录页与工作台顶部展示，留空则不显示',       1, 1, 1, NOW(), 1, NOW(), 0),
('site.footer',       '',             'STRING', 'SITE',     '页脚文案，如版权信息',                               1, 1, 1, NOW(), 1, NOW(), 0),
-- 密码策略与默认密码（FR-CFG-02）
-- 注意 default-password 不是公开项：它只给管理员新建/导入/重置账号时用，
-- 泄露给未登录用户等于把「所有还没改过密码的账号」的钥匙挂在门口。
('security.default-password',      'G14@init2025', 'PASSWORD', 'SECURITY', 'ADMIN 新建账号、批量导入、重置密码时使用的初始密码', 0, 1, 1, NOW(), 1, NOW(), 0),
('security.password.min-length',    '8',            'NUMBER',   'SECURITY', '密码最小长度，取值 6~32',                            1, 1, 1, NOW(), 1, NOW(), 0),
('security.password.max-length',    '32',           'NUMBER',   'SECURITY', '密码最大长度，取值 8~64 且不小于最小长度',            1, 1, 1, NOW(), 1, NOW(), 0),
('security.password.require-letter','1',            'BOOLEAN',  'SECURITY', '是否必须包含字母',                                   1, 1, 1, NOW(), 1, NOW(), 0),
('security.password.require-digit', '1',            'BOOLEAN',  'SECURITY', '是否必须包含数字',                                   1, 1, 1, NOW(), 1, NOW(), 0),
('security.password.require-special','0',           'BOOLEAN',  'SECURITY', '是否必须包含特殊字符（默认关闭，校园场景学生容易记不住）', 1, 1, 1, NOW(), 1, NOW(), 0)
ON DUPLICATE KEY UPDATE config_key = config_key;

-- 自检
SELECT group_name, config_key, config_value, is_public, builtin
FROM sys_config
WHERE deleted = 0
ORDER BY group_name, config_key;
