-- =============================================================
-- 校园问卷信息调查管理系统 —— 04 模板中心建表脚本
-- 数据库：G14(1)  字符集：utf8mb4
-- 依赖：01_schema_auth.sql（用户表）、02_schema_survey.sql（问卷表）
-- 范围：survey_template（模板快照）、template_rating（模板评分）
-- =============================================================

USE `G14(1)`;

DROP TABLE IF EXISTS `template_rating`;
DROP TABLE IF EXISTS `survey_template`;

-- -------------------------------------------------------------
-- 1. 模板：问卷结构的只读快照
-- -------------------------------------------------------------
CREATE TABLE `survey_template` (
  `id`                  BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT COMMENT '主键',
  `title`               VARCHAR(120)     NOT NULL                COMMENT '模板名称（与来源问卷标题可以不同）',
  `description`         VARCHAR(500)              DEFAULT NULL   COMMENT '适用场景说明，供浏览者判断是否合用',
  `category`            VARCHAR(32)      NOT NULL DEFAULT 'OTHER' COMMENT '分类：TEACHING / ACTIVITY / SATISFACTION / OTHER',
  `source_survey_id`    BIGINT UNSIGNED           DEFAULT NULL   COMMENT '来源问卷 ID，仅供追溯；来源问卷被删不影响模板',
  `question_count`      INT              NOT NULL DEFAULT 0      COMMENT '题目数量（冗余，列表页不解析 JSON）',
  `anonymous_default`   TINYINT          NOT NULL DEFAULT 0      COMMENT '使用模板建卷时的匿名默认值',
  `limit_once_default`  TINYINT          NOT NULL DEFAULT 1      COMMENT '使用模板建卷时的限填一次默认值',
  `structure_json`      JSON             NOT NULL                COMMENT '题目结构快照：{"questions":[{...,"options":["A","B"]}]}。选项存文本而不是选项 ID，保证与来源问卷彻底解耦',
  `status`              VARCHAR(16)      NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING 待审核 / APPROVED 已通过 / REJECTED 已驳回 / OFFLINE 已下架',
  `audit_remark`        VARCHAR(255)              DEFAULT NULL   COMMENT '审核意见（驳回时必填，用户在「我的模板」能看到）',
  `audit_by`            BIGINT UNSIGNED           DEFAULT NULL   COMMENT '审核人',
  `audit_time`          DATETIME                  DEFAULT NULL   COMMENT '审核时间',
  `submit_time`         DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '提交审核时间',
  `rating_count`        INT              NOT NULL DEFAULT 0      COMMENT '评分人数',
  `rating_sum`          INT              NOT NULL DEFAULT 0      COMMENT '评分总和。存和而不是平均值，改票时不会因四舍五入累积漂移',
  `rating_avg`          DECIMAL(3,2) GENERATED ALWAYS AS (
                          CASE WHEN `rating_count` = 0 THEN NULL
                               ELSE ROUND(`rating_sum` / `rating_count`, 2) END
                        ) STORED                                COMMENT '平均分（生成列）：无人评分时为 NULL，前端显示「暂无评分」',
  `use_count`           INT              NOT NULL DEFAULT 0      COMMENT '被使用次数',
  `create_by`           BIGINT UNSIGNED           DEFAULT NULL   COMMENT '共享者',
  `create_time`         DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_by`           BIGINT UNSIGNED           DEFAULT NULL,
  `update_time`         DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`             TINYINT          NOT NULL DEFAULT 0      COMMENT '逻辑删除：0=正常 1=已删除',
  PRIMARY KEY (`id`),
  -- 模板中心默认查询：已通过 + 按分类筛选，故 (status, category, deleted) 打头
  KEY `idx_status_category` (`status`, `category`, `deleted`),
  KEY `idx_owner` (`create_by`, `status`, `deleted`),
  -- 按评分排序：无人评分的排在最后，用生成列直接排，避免应用层再排一次
  KEY `idx_rating` (`status`, `rating_avg`, `rating_count`),
  KEY `idx_source` (`source_survey_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='模板（问卷结构快照）';

-- -------------------------------------------------------------
-- 2. 模板评分：一人一模板一票，可改不可重复
-- -------------------------------------------------------------
CREATE TABLE `template_rating` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `template_id` BIGINT UNSIGNED NOT NULL COMMENT '被评分模板',
  `user_id`     BIGINT UNSIGNED NOT NULL COMMENT '评分人（仅学生）',
  `score`       TINYINT         NOT NULL COMMENT '1~5 星',
  `create_time` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  -- 唯一索引就是「一人一票」的并发防线：重复插入由数据库拒绝，而不是靠先查后插
  UNIQUE KEY `uk_template_user` (`template_id`, `user_id`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='模板评分';

-- -------------------------------------------------------------
-- 设计说明（写在这里，避免只存在于某个人的脑子里）
--
-- 1) 为什么题目结构存 JSON 而不建 template_question / template_option 两张表？
--    模板结构只有两种读取方式：详情页整卷展示、使用模板时整卷复制，从来没有
--    「按某道题筛选模板」或「跨模板统计某题」这类查询。也就是说它永远是整块读整块写，
--    这正是 JSON 的适用场景。建两张子表要多写两套差量同步代码，
--    却换不来任何查询能力。反面例子是 answer_item：统计要按题聚合，所以必须拆表。
--
-- 2) 为什么 option 存文本而不是选项 ID？
--    R-TPL-1 要求模板与问卷解耦。若存选项 ID，来源问卷被修改或删除后，
--    模板详情会指向不存在的选项。存文本才是真正的快照。
--
-- 3) 为什么存 rating_sum + rating_count 而不是直接存 rating_avg？
--    改票时若存平均值，需要「反算再四舍五入」，反复改会累积误差。
--    存和与人数可以精确增减，平均值由生成列实时算出，永远一致。
--    生成列同时给了索引一个可排序的字段，列表页不需要回表算平均。
--
-- 4) 为什么模板没有 DELETED 状态码？
--    与 survey 同理：删除由 deleted=1 表达。status 里再放一个 DELETED
--    会出现「status=DELETED 但 deleted=0」这种自相矛盾的数据。
-- =============================================================
