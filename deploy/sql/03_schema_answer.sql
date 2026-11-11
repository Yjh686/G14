-- =============================================================
-- 校园问卷信息调查管理系统 —— 03 答卷与草稿建表脚本
-- 数据库：G14(1)  字符集：utf8mb4
-- 依赖：01_schema_auth.sql（用户表）、02_schema_survey.sql（问卷表）
-- 范围：answer_sheet（答卷头）、answer_item（答案明细）、survey_draft（草稿）
-- =============================================================

USE `G14(1)`;

DROP TABLE IF EXISTS `survey_draft`;
DROP TABLE IF EXISTS `answer_item`;
DROP TABLE IF EXISTS `answer_sheet`;

-- -------------------------------------------------------------
-- 1. 答卷头：一次完整提交
-- -------------------------------------------------------------
CREATE TABLE `answer_sheet` (
  `id`             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键，同时作为答卷编号对外展示',
  `survey_id`      BIGINT UNSIGNED NOT NULL                COMMENT '所属问卷',
  `user_id`        BIGINT UNSIGNED NOT NULL                COMMENT '填写人。匿名问卷同样写入，仅用于限填一次与「我的记录」；任何面向发布者的接口都不得返回该字段',
  `limit_once`     TINYINT         NOT NULL DEFAULT 1      COMMENT '提交时对 survey.limit_once 的快照，决定是否占用唯一槽位',
  `is_valid`       TINYINT         NOT NULL DEFAULT 1      COMMENT '是否有效：1=有效（计入统计）0=已标记无效',
  `invalid_reason` VARCHAR(255)             DEFAULT NULL   COMMENT '标记无效的原因',
  `create_time`    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '提交时间',
  `update_time`    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后修改时间',
  `deleted`        TINYINT         NOT NULL DEFAULT 0      COMMENT '逻辑删除：0=正常 1=已删除',
  -- 唯一槽位：限填一次的问卷占用 user_id，可重复填写的问卷为 NULL。
  -- 唯一索引允许多个 NULL，于是同一索引同时满足两种语义，且由数据库保证并发下不会出现两份有效答卷。
  `uniq_user`      BIGINT UNSIGNED GENERATED ALWAYS AS (
                     CASE WHEN `limit_once` = 1 AND `deleted` = 0 THEN `user_id` ELSE NULL END
                   ) STORED                                 COMMENT '唯一槽位（生成列）：限填一次时=填写人，否则 NULL',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_survey_user` (`survey_id`, `uniq_user`),
  KEY `idx_survey_time` (`survey_id`, `deleted`, `create_time`),
  KEY `idx_user_time` (`user_id`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='答卷头（一次提交）';

-- -------------------------------------------------------------
-- 2. 答案明细：一行一题
-- -------------------------------------------------------------
CREATE TABLE `answer_item` (
  `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `sheet_id`      BIGINT UNSIGNED NOT NULL                COMMENT '所属答卷',
  `survey_id`     BIGINT UNSIGNED NOT NULL                COMMENT '冗余问卷 ID，统计时按问卷直接聚合，免一次 join',
  `question_id`   BIGINT UNSIGNED NOT NULL                COMMENT '题目 ID',
  `option_values` JSON                     DEFAULT NULL   COMMENT '选项类答案：选项 ID 数组，如 [12,15]；单选也存数组，统一取值方式',
  `text_value`    TEXT                     DEFAULT NULL   COMMENT '文本题答案',
  `number_value`  INT                      DEFAULT NULL   COMMENT '评分题分值（1..rating_max）',
  `create_time`   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_sheet_question` (`sheet_id`, `question_id`),
  KEY `idx_survey_question` (`survey_id`, `question_id`),
  KEY `idx_sheet` (`sheet_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='答案明细（一行一题）';

-- -------------------------------------------------------------
-- 3. 草稿：整卷 JSON，不参与统计
-- -------------------------------------------------------------
CREATE TABLE `survey_draft` (
  `id`           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `survey_id`    BIGINT UNSIGNED NOT NULL                COMMENT '所属问卷',
  `user_id`      BIGINT UNSIGNED NOT NULL                COMMENT '填写人',
  `answers_json` JSON            NOT NULL                COMMENT '整卷作答 JSON，结构与提交请求一致，允许缺题（未答完）',
  `create_time`  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '首次保存时间',
  `update_time`  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后保存时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_survey_user` (`survey_id`, `user_id`),
  KEY `idx_user_time` (`user_id`, `update_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='填写草稿（断点续答）';

-- =============================================================
-- 设计说明（后续改动请同步更新本节）
-- -------------------------------------------------------------
-- 1) 为什么唯一索引挂在生成列上，而不是直接 (survey_id, user_id)？
--    survey.limit_once=0 时允许同一人重复填写，若直接对 (survey_id, user_id)
--    建唯一索引，这类问卷就无法使用。生成列把「需不需要占槽」的判定下推到数据库：
--    限填一次的问卷 uniq_user=user_id（冲突即拒绝），可重复填写的问卷 uniq_user=NULL
--    （唯一索引不约束 NULL）。这样「限填一次」由数据库在并发下强制保证，
--    服务层的预检查只是给用户友好提示，不是安全边界。
--
-- 2) 为什么 answer_sheet 不记录 IP / User-Agent？
--    匿名问卷若记录 IP，结合访问日志可反推身份，匿名承诺就失效了。
--    非匿名场景也不需要 IP —— 归属已由 user_id 表达。故整表不留任何网络痕迹。
--
-- 3) 为什么 answer_item.option_values 用 JSON 而不是单独一张选项表？
--    单选题一份答卷一个选项，多选题 N 个。若拆表，多选要插 N 行，
--    统计时还要 GROUP BY 两次。JSON 数组让一题恒为一行，写入是固定次数，
--    统计用 JSON_CONTAINS / JSON_LENGTH 即可（MySQL 8 原生支持）。
--
-- 4) 草稿为什么存整卷 JSON 而不是明细表？
--    草稿不参与统计、不参与导出，唯一的用途是原样还回前端。
--    存 JSON 免去「增删改题目行」的同步成本，也天然支持「只答了一半」。
--    提交成功后删除对应草稿，避免草稿与正式答卷两份数据不一致。
--
-- 5) survey.answer_count 的语义是「有效答卷数」（不是回收总数）：
--    提交 +1，标记无效 -1，恢复有效 +1，删除有效答卷 -1。
--    统计口径固定为有效答卷，这样 answer_count 可直接用于完成率与列表展示，
--    不需要每次再扫 answer_sheet。
-- =============================================================
