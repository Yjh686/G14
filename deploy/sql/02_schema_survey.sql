-- =============================================================
-- 校园问卷信息调查管理系统 —— 02 问卷与题目建表脚本
-- 数据库：G14(1)  字符集：utf8mb4
-- 依赖：01_schema_auth.sql（本脚本不建用户/权限表）
-- 范围：survey（问卷）、survey_question（题目）、survey_question_option（选项）
--       答卷/草稿/模板/统计相关表在 03_*.sql 补充
-- =============================================================

USE `G14(1)`;

-- -------------------------------------------------------------
-- 1. 问卷主表
-- -------------------------------------------------------------
DROP TABLE IF EXISTS `survey_question_option`;
DROP TABLE IF EXISTS `survey_question`;
DROP TABLE IF EXISTS `survey`;

CREATE TABLE `survey` (
  `id`             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `title`          VARCHAR(120)    NOT NULL                COMMENT '问卷标题',
  `description`    VARCHAR(1000)            DEFAULT NULL   COMMENT '问卷简介/填写说明',
  `category`       VARCHAR(32)     NOT NULL DEFAULT 'OTHER' COMMENT '分类：TEACHING 教学反馈 / ACTIVITY 活动报名 / SATISFACTION 满意度 / OTHER 其他',
  `anonymous`      TINYINT         NOT NULL DEFAULT 0      COMMENT '是否匿名：1=匿名（发布者看不到填写人）0=实名',
  `status`         VARCHAR(16)     NOT NULL DEFAULT 'DRAFT' COMMENT '状态：DRAFT 草稿 / PUBLISHED 已发布 / PAUSED 已暂停 / CLOSED 已结束（删除由 deleted 表达，不在此列）',
  `share_code`     VARCHAR(16)              DEFAULT NULL   COMMENT '分享码（8 位，发布时生成，全局唯一）',
  `start_time`     DATETIME                 DEFAULT NULL   COMMENT '开始时间，NULL=立即开始',
  `end_time`       DATETIME                 DEFAULT NULL   COMMENT '截止时间，NULL=长期有效',
  `limit_once`     TINYINT         NOT NULL DEFAULT 1      COMMENT '是否限填一次：1=每人一份 0=可重复填写',
  `question_count` INT             NOT NULL DEFAULT 0      COMMENT '题目数量（冗余，供列表页展示，避免 count 子查询）',
  `answer_count`   INT             NOT NULL DEFAULT 0      COMMENT '已回收答卷数（冗余，提交时 +1；无效/删除答卷在答卷模块扣减）',
  `published_time` DATETIME                 DEFAULT NULL   COMMENT '首次发布时间',
  `closed_time`    DATETIME                 DEFAULT NULL   COMMENT '结束时间（人为提前结束时记录）',
  `create_by`      BIGINT UNSIGNED          DEFAULT NULL   COMMENT '创建人（归属校验依据）',
  `create_time`    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_by`      BIGINT UNSIGNED          DEFAULT NULL   COMMENT '最后修改人',
  `update_time`    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后修改时间',
  `deleted`        TINYINT         NOT NULL DEFAULT 0      COMMENT '逻辑删除：0=正常 1=已删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_share_code` (`share_code`),
  KEY `idx_owner` (`create_by`, `status`, `deleted`),
  KEY `idx_status_time` (`status`, `end_time`),
  KEY `idx_create_time` (`create_time`),
  KEY `idx_title` (`title`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='问卷主表';

-- -------------------------------------------------------------
-- 2. 题目表
--    说明：草稿阶段题目可任意增删改，因此物理删除、不做逻辑删除。
--         草稿没有答卷关联，删掉无需审计追溯（操作日志已记录动作）。
-- -------------------------------------------------------------
CREATE TABLE `survey_question` (
  `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `survey_id`     BIGINT UNSIGNED NOT NULL                COMMENT '所属问卷 ID',
  `question_type` VARCHAR(16)     NOT NULL                COMMENT '题型：SINGLE 单选 / MULTIPLE 多选 / TEXT 单行文本 / TEXTAREA 多行文本 / RATING 评分',
  `title`         VARCHAR(255)    NOT NULL                COMMENT '题干',
  `description`   VARCHAR(500)             DEFAULT NULL   COMMENT '题目补充说明',
  `is_required`   TINYINT         NOT NULL DEFAULT 0      COMMENT '是否必答：1=必答 0=选答',
  `max_length`    INT                      DEFAULT NULL   COMMENT '文本题最大字数（仅 TEXT/TEXTAREA 有效）',
  `rating_max`    TINYINT                  DEFAULT NULL   COMMENT '评分题最高分（仅 RATING 有效，默认 5）',
  `sort_no`       INT             NOT NULL DEFAULT 0      COMMENT '排序号，从 1 递增',
  `create_by`     BIGINT UNSIGNED          DEFAULT NULL   COMMENT '创建人',
  `create_time`   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_by`     BIGINT UNSIGNED          DEFAULT NULL   COMMENT '最后修改人',
  `update_time`   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后修改时间',
  PRIMARY KEY (`id`),
  KEY `idx_survey_sort` (`survey_id`, `sort_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='问卷题目表';

-- -------------------------------------------------------------
-- 3. 选项表
--    survey_id 冗余存储：统计「某问卷各选项被选次数」时直接按 survey_id 聚合，
--    避免每次都要 join 题目表；写入时由服务层保证与题目的 survey_id 一致。
-- -------------------------------------------------------------
CREATE TABLE `survey_question_option` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `question_id` BIGINT UNSIGNED NOT NULL                COMMENT '所属题目 ID',
  `survey_id`   BIGINT UNSIGNED NOT NULL                COMMENT '所属问卷 ID（冗余，便于按问卷聚合统计）',
  `content`     VARCHAR(255)    NOT NULL                COMMENT '选项文本',
  `sort_no`     INT             NOT NULL DEFAULT 0      COMMENT '排序号，从 1 递增',
  `create_time` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_question_sort` (`question_id`, `sort_no`),
  KEY `idx_survey` (`survey_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='问卷选项表';

-- =============================================================
-- 说明（写在这里便于后续维护者理解设计）
--
-- 1) 为什么 status 里没有 DELETED：
--    「已删除」由 deleted=1 表达，再由 MyBatis-Plus 自动过滤。
--    若 status 也存 DELETED，就有两处状态需要同步，容易出现
--    「status=CLOSED 但 deleted=1」这类不一致。接口层永远不会返回
--    DELETED 这个状态值，SRS 状态机里的 DELETED 语义等价于 deleted=1。
--
-- 2) 为什么 question_count / answer_count 冗余在 survey 上：
--    问卷列表页每行都要显示题量与回收量。若用子查询或 count(*)
--    聚合，列表页会变成 N+1 次查询（<=200 份问卷时仍是几十次）。
--    冗余字段在保存题目、提交答卷时同步更新，代价可控。
--
-- 3) 为什么 share_code 可空且唯一：
--    MySQL 的唯一索引允许多个 NULL，正好满足「草稿还没分享码」
--    又能保证「已发布问卷的分享码不重复」。
--
-- 4) 为什么子表不做逻辑删除：
--    题目/选项只在 DRAFT 阶段可改，草稿没有答卷，删掉就是脏数据
--    而非需要追溯的历史。保留 deleted 反而让「差量保存」逻辑复杂化。
-- =============================================================
