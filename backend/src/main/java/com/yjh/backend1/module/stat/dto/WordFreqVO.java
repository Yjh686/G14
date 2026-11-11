package com.yjh.backend1.module.stat.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 词频条目（FR-STAT-03）。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class WordFreqVO {

    private String word;

    private long count;
}
