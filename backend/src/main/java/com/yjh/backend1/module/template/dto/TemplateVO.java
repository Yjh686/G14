package com.yjh.backend1.module.template.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 模板列表项 / 详情（不含题目结构）。
 * <p>
 * {@code structure} 只在详情接口里填，列表页留 null：
 * 模板中心一页 20 个模板，每个都带上整卷题目会让响应体膨胀十倍以上，
 * 而列表卡片只需要标题、分类、题量、评分。
 */
@Data
public class TemplateVO {

    private Long id;
    private String title;
    private String description;
    private String category;
    /** 分类中文名，前端直接展示 */
    private String categoryLabel;
    private Long sourceSurveyId;
    private Integer questionCount;
    private Integer anonymousDefault;
    private Integer limitOnceDefault;

    /** PENDING / APPROVED / REJECTED / OFFLINE */
    private String status;
    private String statusLabel;
    private String auditRemark;
    private Long auditBy;
    private String auditByName;
    private LocalDateTime auditTime;
    private LocalDateTime submitTime;

    private Integer ratingCount;
    /** 平均分，无人评分时为 null（前端显示「暂无评分」，不要伪装成 0 分） */
    private BigDecimal ratingAvg;
    /** 当前登录用户给出的分数，未评过为 null */
    private Integer myScore;
    private Integer useCount;

    private Long createBy;
    /** 共享者姓名 */
    private String createByName;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    /**
     * 题目结构快照，仅详情接口返回。
     * 用 {@link TemplateQuestionVO} 而不是问卷模块的 QuestionVO：选项是纯文本，没有选项 ID。
     */
    private List<TemplateQuestionVO> structure;
}
