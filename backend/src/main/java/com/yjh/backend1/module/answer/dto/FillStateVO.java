package com.yjh.backend1.module.answer.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 填写页的进入状态。
 * <p>
 * 前端打开填写页先请求这一个接口，拿到「能不能填 / 已填过没有 / 上次草稿是什么」，
 * 再决定渲染填写表单、已提交回执页，还是不可填写提示页。
 * <p>
 * 把这三件事合在一个接口里，是为了避免前端串行发三个请求：
 * 学生常在课间用手机填，多点一次往返就多一次失败机会。
 */
@Data
public class FillStateVO {

    private Long surveyId;
    private String surveyTitle;

    /** 当前是否可填写（状态 + 时间窗 + 是否已提交，三者同时满足） */
    private Boolean fillable;

    /**
     * 不可填写的原因，可直接展示给用户：
     * NOT_STARTED / PAUSED / CLOSED / EXPIRED / ALREADY_SUBMITTED。
     * 可填写时为 null。
     */
    private String reason;

    /** 已提交则给出回执信息；未提交为 null */
    private Boolean submitted;
    private Long sheetId;
    private LocalDateTime submitTime;

    /** 上次未提交的草稿；没有草稿为 null（不是空数组，便于前端区分「没答过」和「答了但全清空」） */
    private java.util.List<AnswerInput> draftAnswers;
    /** 草稿最后保存时间 */
    private LocalDateTime draftTime;
}
