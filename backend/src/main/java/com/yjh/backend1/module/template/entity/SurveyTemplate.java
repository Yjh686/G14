package com.yjh.backend1.module.template.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yjh.backend1.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 模板实体，对应表 {@code survey_template}。
 * <p>
 * 模板是问卷结构的<b>只读快照</b>，不是指向问卷的引用：
 * 来源问卷后续被改题、被发布、被删除，模板内容都不受影响（R-TPL-1）。
 * 这也是 {@code structureJson} 存整卷 JSON 的原因 —— 见建表脚本里的设计说明。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("survey_template")
public class SurveyTemplate extends BaseEntity {

    /** 模板名称，与来源问卷标题可以不同 */
    private String title;
    /** 适用场景说明 */
    private String description;
    /** 分类：TEACHING / ACTIVITY / SATISFACTION / OTHER */
    private String category;
    /** 来源问卷 ID，仅供追溯；来源被删不影响模板 */
    private Long sourceSurveyId;
    /** 题目数量（冗余，列表页不用解析 JSON） */
    private Integer questionCount;
    /** 使用模板建卷时的匿名默认值 */
    private Integer anonymousDefault;
    /** 使用模板建卷时的限填一次默认值 */
    private Integer limitOnceDefault;
    /** 题目结构快照 JSON */
    private String structureJson;

    /** 状态：PENDING / APPROVED / REJECTED / OFFLINE */
    private String status;
    /** 审核意见，驳回时必填 */
    private String auditRemark;
    private Long auditBy;
    private LocalDateTime auditTime;
    private LocalDateTime submitTime;

    /** 评分人数 */
    private Integer ratingCount;
    /** 评分总和（存和而不是平均值，改票不会累积舍入误差） */
    private Integer ratingSum;

    /**
     * 平均分，数据库生成列。
     * <p>
     * 写入策略设为 NEVER：这个值由 MySQL 根据 rating_sum / rating_count 实时算出，
     * 若让 MyBatis-Plus 一并 update，会报「The value specified for generated column
     * 'rating_avg' in table 'survey_template' is not allowed」。
     */
    @TableField(value = "rating_avg", insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private BigDecimal ratingAvg;

    /** 被使用次数 */
    private Integer useCount;

    public boolean approved() {
        return "APPROVED".equals(status);
    }

    public boolean pending() {
        return "PENDING".equals(status);
    }
}
