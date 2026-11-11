package com.yjh.backend1.module.answer.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 「我的填写记录」列表项（FR-FILL-07）。
 * <p>
 * 匿名问卷只回执「已提交 + 时间」，<b>不回显作答内容</b>（FR-FILL-08）。
 * 字段 {@code contentVisible} 就是给前端判断用的：false 时详情页只显示回执。
 */
@Data
public class MyAnswerVO {

    private Long sheetId;
    private Long surveyId;
    private String surveyTitle;
    private String surveyStatus;

    private LocalDateTime submitTime;

    /** 答卷是否被判为无效（学生能看到，避免「我明明交了却查不到」） */
    private Integer isValid;

    /**
     * 答卷内容对填写人本人是否可见。
     * <p>
     * 匿名问卷为 <b>false</b>：如果允许本人回看，等于给了一份可截图的副本，
     * 而匿名问卷的场景（课程评价、意见征集）恰恰需要「提交后不可追溯」的心理安全感。
     * 非匿名问卷为 true。
     */
    private Boolean contentVisible;
}
