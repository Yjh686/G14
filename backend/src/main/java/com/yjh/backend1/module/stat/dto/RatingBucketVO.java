package com.yjh.backend1.module.stat.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 单个星级的分布（FR-STAT-04）。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RatingBucketVO {

    /** 星级，1..ratingMax */
    private int star;

    private long count;

    /** 占参评人数的比例，0~1 */
    private double ratio;
}
