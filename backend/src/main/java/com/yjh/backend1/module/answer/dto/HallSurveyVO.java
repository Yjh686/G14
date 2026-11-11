package com.yjh.backend1.module.answer.dto;

import com.yjh.backend1.module.survey.dto.SurveyVO;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 问卷大厅卡片项（FR-FILL-01）。
 * <p>
 * 在问卷基本信息之上叠加「我和这份问卷的关系」，前端据此决定卡片上的按钮：
 * 未作答 → 立即填写；有草稿 → 继续填写；已提交 → 已提交（置灰）。
 * <p>
 * 把这些状态放在服务端算，是因为「已填」的判定依赖 answer_sheet 与 survey_draft
 * 两张表，前端拿不到。若放到前端，要么多一次请求，要么只好把「已填」写成不可靠的乐观假设。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class HallSurveyVO extends SurveyVO {

    /**
     * 我对这份问卷的状态：
     * NOT_STARTED（未作答）/ DRAFT（有草稿）/ SUBMITTED（已提交）。
     * <p>
     * 注意与 {@code fillable} 的区别：{@code fillable} 说的是「这份问卷现在收不收卷」，
     * {@code myStatus} 说的是「我这一侧能不能填」。一张卡片同时需要两者：
     * 问卷开着但我已提交 → 按钮显示「已提交」；问卷暂停了我还没填 → 显示「已暂停回收」。
     */
    private String myStatus;

    /** 我的草稿最后保存时间；无草稿为 null */
    private java.time.LocalDateTime draftTime;

    /** 我的提交时间；未提交为 null */
    private java.time.LocalDateTime submitTime;
}
