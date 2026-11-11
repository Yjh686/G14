package com.yjh.backend1.module.stat.dto;

import lombok.Data;

import java.util.List;

/**
 * 评分题的统计结果（FR-STAT-04）。
 * <p>
 * {@code distribution} 按星级 1..ratingMax 顺序给出，没被选过的星级也有条目（count=0），
 * 这样前端画柱状图不用自己补空档；缺档会让 X 轴跳号，看起来像数据错。
 */
@Data
public class RatingStatsVO {

    /** 参评人数 */
    private long answeredCount;

    /** 平均分，保留 2 位小数；无作答时为 0 */
    private double average;

    /** 该题的星级上限（问卷定义时的 ratingMax，缺省 5） */
    private int ratingMax;

    /** 星级分布，顺序即 1..ratingMax */
    private List<RatingBucketVO> distribution;
}
