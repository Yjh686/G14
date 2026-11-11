package com.yjh.backend1.module.stat.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 一条文本作答（FR-STAT-03）。
 * <p>
 * 刻意不含填写人：文本统计同样受匿名约束，即使问卷非匿名，
 * 统计页只需要「答了什么」，不需要「谁答的」——那是答卷详情的事。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TextAnswerVO {

    private String text;

    private LocalDateTime submitTime;
}
