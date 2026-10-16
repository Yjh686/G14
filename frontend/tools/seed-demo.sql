-- 演示用班级 / 问卷数据（班级与问卷模块尚未交付，这里手工造数据，
-- 目的是让前端联调和权限校验有真实的资源可指向）。
--
-- 依赖：先跑 `npm run seed` 建好演示账号，再执行本脚本。
-- 可重复执行：用 INSERT ... WHERE NOT EXISTS 保证幂等。
--
-- 用法：
--   mysql -u root -p class_survey < tools/seed-demo.sql

-- 班级 1：老师甲的班级，学生一已加入
INSERT INTO class_info (name, invite_code, teacher_id)
SELECT '高一(1)班', 'INVITE-A', u.id
FROM sys_user u
WHERE u.account = 'T1001'
  AND NOT EXISTS (SELECT 1 FROM class_info c WHERE c.invite_code = 'INVITE-A');

-- 班级 2：老师乙的班级（学生一**不在**其中，用于验证水平越权被拦）
INSERT INTO class_info (name, invite_code, teacher_id)
SELECT '高一(2)班', 'INVITE-B', u.id
FROM sys_user u
WHERE u.account = 'T1002'
  AND NOT EXISTS (SELECT 1 FROM class_info c WHERE c.invite_code = 'INVITE-B');

-- 学生一加入班级 1
INSERT INTO class_student (class_id, student_id)
SELECT c.id, u.id
FROM class_info c, sys_user u
WHERE c.invite_code = 'INVITE-A'
  AND u.account = '2021001'
  AND NOT EXISTS (
    SELECT 1 FROM class_student cs WHERE cs.class_id = c.id AND cs.student_id = u.id
  );

-- 班级 1 下的一张已发布问卷
INSERT INTO questionnaire (class_id, title, creator_id, status)
SELECT c.id, '学习情况调查', u.id, 1
FROM class_info c, sys_user u
WHERE c.invite_code = 'INVITE-A'
  AND u.account = 'T1001'
  AND NOT EXISTS (
    SELECT 1 FROM questionnaire q WHERE q.class_id = c.id AND q.title = '学习情况调查'
  );

-- 班级 1 下的一张草稿问卷（学生不可见，老师可见）
INSERT INTO questionnaire (class_id, title, creator_id, status)
SELECT c.id, '未发布草稿', u.id, 0
FROM class_info c, sys_user u
WHERE c.invite_code = 'INVITE-A'
  AND u.account = 'T1001'
  AND NOT EXISTS (
    SELECT 1 FROM questionnaire q WHERE q.class_id = c.id AND q.title = '未发布草稿'
  );
