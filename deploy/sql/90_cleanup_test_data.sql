-- =============================================================
-- 90_cleanup_test_data.sql
-- 用途：清理冒烟脚本与 E2E 脚本留下的测试数据，只保留演示数据。
--
-- 保留：
--   survey id=1  教学质量反馈问卷（PUBLISHED，share_code=Q3PTC5RC）
--   survey id=13 实名签到问卷（PUBLISHED，share_code=94LNUAY4）
--   sys_user id=1/2/3（admin / teacher / student）
--
-- 删除：
--   其它所有问卷（含 deleted=1 的历史行）、它们的题目与选项
--   这些问卷下的答卷与草稿
--   除 1/2/3 之外的账号（测试注册、冒烟新建的账号）
--   冒烟新建的自定义配置项（sys_config.builtin=0）
--   登录日志与操作日志（只留最近 7 天）
--
-- 用法：
--   & "C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe" -u root -p --default-character-set=utf8mb4 ^
--       --default-character-set=utf8mb4 -e "source D:/G14(1)/deploy/sql/90_cleanup_test_data.sql" "g14(1)"
-- =============================================================

-- 1) 先删子表，避免留下孤立行
DELETE FROM answer_item
 WHERE sheet_id IN (SELECT id FROM answer_sheet WHERE survey_id NOT IN (1, 13));

DELETE FROM answer_sheet
 WHERE survey_id NOT IN (1, 13);

DELETE FROM survey_draft
 WHERE survey_id NOT IN (1, 13);

DELETE FROM survey_question_option
 WHERE survey_id NOT IN (1, 13);

DELETE FROM survey_question
 WHERE survey_id NOT IN (1, 13);

-- 2) 模板：冒烟与 E2E 造的模板全部删掉（没有演示模板）
DELETE FROM template_rating;

DELETE FROM survey_template;

-- 3) 问卷主表：只留两份演示问卷
DELETE FROM survey
 WHERE id NOT IN (1, 13);

-- 4) 演示问卷的计数归零（测试提交留下的数字）
UPDATE survey SET answer_count = 0 WHERE id IN (1, 13);

-- 5) 清理测试账号（先删关联表再删主表）
-- 密保表与找回日志没有逻辑删除，必须显式删：sys_user_recovery 有 uk_user 唯一键，
-- 若不删，将来复用同一个 id 建账号会直接撞唯一键。
DELETE FROM sys_user_recovery
 WHERE user_id NOT IN (1, 2, 3);

DELETE FROM sys_recovery_log
 WHERE user_id IS NOT NULL AND user_id NOT IN (1, 2, 3);

DELETE FROM sys_user_role
 WHERE user_id NOT IN (1, 2, 3);

DELETE FROM sys_user_permission
 WHERE user_id NOT IN (1, 2, 3);

DELETE FROM sys_user
 WHERE id NOT IN (1, 2, 3);

-- 6) 配置：只保留内置项（13 个：SITE 4 + SECURITY 6 + RECOVERY 3）
-- 冒烟脚本新建的自定义项连墓碑行一起清掉
DELETE FROM sys_config WHERE builtin = 0;

-- 7) 日志只留最近 7 天（日志表只增不改，这里是有意的例外：清理测试噪音）
DELETE FROM sys_login_log    WHERE create_time < DATE_SUB(NOW(), INTERVAL 7 DAY);
DELETE FROM sys_operation_log WHERE create_time < DATE_SUB(NOW(), INTERVAL 7 DAY);

-- 8) 结果自检
SELECT 'survey'        AS tbl, COUNT(*) AS row_cnt FROM survey
UNION ALL SELECT 'survey_question',        COUNT(*) FROM survey_question
UNION ALL SELECT 'survey_question_option', COUNT(*) FROM survey_question_option
UNION ALL SELECT 'answer_sheet',           COUNT(*) FROM answer_sheet
UNION ALL SELECT 'answer_item',            COUNT(*) FROM answer_item
UNION ALL SELECT 'survey_draft',           COUNT(*) FROM survey_draft
UNION ALL SELECT 'survey_template',        COUNT(*) FROM survey_template
UNION ALL SELECT 'template_rating',        COUNT(*) FROM template_rating
UNION ALL SELECT 'sys_config',             COUNT(*) FROM sys_config
UNION ALL SELECT 'sys_user_recovery',      COUNT(*) FROM sys_user_recovery
UNION ALL SELECT 'sys_recovery_log',       COUNT(*) FROM sys_recovery_log
UNION ALL SELECT 'sys_user',               COUNT(*) FROM sys_user;
