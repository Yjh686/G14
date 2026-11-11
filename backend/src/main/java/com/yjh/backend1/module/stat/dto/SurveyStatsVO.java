package com.yjh.backend1.module.stat.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 整卷统计结果（FR-STAT-01）。
 * <p>
 * 一个接口返回「卷头概览 + 逐题统计」：统计页是一屏滚动，分接口只会让前端多等一次往返。
 * <p>
 * {@code validSheetCount} 与 {@code totalSheetCount} 的差就是无效答卷数，
 * 前端要显式展示「已排除 N 份无效答卷」，否则用户会疑惑为什么答卷列表里的条数比统计人数多。
 */
@Data
public class SurveyStatsVO {

    private Long surveyId;

    private String surveyTitle;

    private String category;

    private String categoryLabel;

    private String status;

    private boolean anonymous;

    /** 回收总数（含无效） */
    private long totalSheetCount;

    /** 有效答卷数 —— 所有统计数字的分母（FR-STAT-06） */
    private long validSheetCount;

    private LocalDateTime publishedTime;

    /** 统计生成时间，前端显示「数据更新于 xx」 */
    private LocalDateTime generatedAt;

    private List<QuestionStatsVO> questions;
}
