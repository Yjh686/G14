package com.yjh.backend1.module.answer.dto;

import lombok.Data;

/**
 * 问卷回收概览（FR-ANS-05）。
 * <p>
 * <b>口径必须写在字段注释里，否则三个人会算出三个数。</b>
 * 本项目统一：
 * <ul>
 *   <li>{@code total} = 已回收答卷数（含已标记无效的，即「交了多少份」）</li>
 *   <li>{@code validCount} = 有效答卷数（计入统计的那个数，= {@code survey.answer_count}）</li>
 *   <li>{@code invalidCount} = 被标记无效的答卷数</li>
 *   <li>{@code completionRate} = 有效答卷数 ÷ 应填人数，保留 4 位小数</li>
 * </ul>
 */
@Data
public class AnswerSummaryVO {

    private Long surveyId;
    private String surveyTitle;

    /** 已回收总数（含无效） */
    private Long total;
    /** 有效答卷数（统计口径） */
    private Long validCount;
    /** 无效答卷数 */
    private Long invalidCount;

    /**
     * 完成率 = 有效答卷数 / 应填人数。
     * <p>
     * 「应填人数」本期取<b>启用状态的学生账号总数</b> —— 因为还没实现「指定填答班级/名单」，
     * 无法知道某份问卷到底该由哪些人填。这是<b>近似口径</b>，会在接口文档里写明；
     * 等填答范围功能上线后，分母改为实际名单人数，字段语义不变。
     * 分母为 0 时返回 0，不返回 NaN。
     */
    private Double completionRate;
    /** 计算完成率时用的分母，一并返回，便于前端展示「12/300」 */
    private Long expectedCount;

    /** 问卷是否匿名，前端据此决定概览页要不要显示「填写人」相关入口 */
    private Boolean anonymous;
}
