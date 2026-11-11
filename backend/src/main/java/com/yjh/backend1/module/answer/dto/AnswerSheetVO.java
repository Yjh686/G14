package com.yjh.backend1.module.answer.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 答卷列表项。
 * <p>
 * <b>匿名问卷时 {@code userId} / {@code fillerName} / {@code fillerUsername} 必须为 null。</b>
 * 这不是「前端不展示」，而是后端根本不返回 —— 只要返回了，打开浏览器开发者工具就能看到，
 * 匿名承诺就形同虚设。裁剪逻辑统一在 {@code AnswerService.toSheetVO} 里，
 * 任何新的出口（列表、详情、导出）都必须走它。
 */
@Data
public class AnswerSheetVO {

    /** 答卷编号（即主键），对外展示用 */
    private Long id;

    private Long surveyId;
    private String surveyTitle;

    /** 是否匿名问卷。前端据此决定表头显不显示「填写人」列 */
    private Boolean anonymous;

    /** 提交时间 */
    private LocalDateTime submitTime;

    /** 是否有效：1=有效 0=已标记无效 */
    private Integer isValid;
    private String invalidReason;

    /**
     * 填写人信息。匿名问卷一律为 null。
     * <p>非匿名问卷才返回，用于教师核对「谁没交」。
     */
    private Long userId;
    private String fillerName;
    private String fillerUsername;
}
