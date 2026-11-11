package com.yjh.backend1.module.survey.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 问卷列表项（不含题目，列表页不需要）。
 */
@Data
public class SurveyVO {

    private Long id;
    private String title;
    private String description;
    private String category;
    /** 分类中文名，前端直接展示，省得再维护一份映射 */
    private String categoryLabel;
    /** 是否匿名：1=是 0=否 */
    private Integer anonymous;
    /** 状态：DRAFT / PUBLISHED / PAUSED / CLOSED */
    private String status;
    private String shareCode;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private Integer limitOnce;
    private Integer questionCount;
    private Integer answerCount;
    private LocalDateTime publishedTime;
    private LocalDateTime closedTime;
    private Long createBy;
    /** 创建人姓名，ADMIN 看全量时的必要信息 */
    private String createByName;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    /**
     * 当前是否可填写（状态 + 时间窗双重判断）。
     * 让前端列表/卡片直接拿这个字段决定按钮状态，避免前端重复实现时间判断。
     */
    private Boolean fillable;
}
