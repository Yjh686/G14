package com.yjh.backend1.module.stat.dto;

import lombok.Data;

/**
 * 单个选项的统计结果（FR-STAT-02）。
 * <p>
 * {@code ratio} 的分母是「该题的有效作答人数」，不是「选项总数之和」：
 * 多选题一个人可选多项，各选项占比相加会超过 100%，这是正确语义而非 bug。
 * 前端展示时要写清口径，否则会被误读成「占比加起来不等于 1 说明算错了」。
 */
@Data
public class OptionStatsVO {

    private Long optionId;

    private String content;

    /** 选择该选项的人数（多选题里同一个人只计一次） */
    private long count;

    /** 占该题有效作答人数的比例，0~1，保留 4 位小数 */
    private double ratio;
}
