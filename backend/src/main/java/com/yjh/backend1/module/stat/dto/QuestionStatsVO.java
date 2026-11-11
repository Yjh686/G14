package com.yjh.backend1.module.stat.dto;

import lombok.Data;

import java.util.List;

/**
 * 单题统计结果（FR-STAT-01）。
 * <p>
 * 五种题型共用一个 VO，按 {@code questionType} 决定哪个子结构有值，其余为 null：
 * <table border="1">
 *   <tr><th>questionType</th><th>有值的字段</th></tr>
 *   <tr><td>SINGLE / MULTIPLE</td><td>options</td></tr>
 *   <tr><td>TEXT / TEXTAREA</td><td>text</td></tr>
 *   <tr><td>RATING</td><td>rating</td></tr>
 * </table>
 * 不拆成五个接口：前端是「逐题往下滚」的一页，拆开就得发 N 个请求，
 * 而这里的数据本来就是一个 SQL 聚合的结果集按题分组。
 * <p>
 * {@code answeredCount} 放在外层而不是各子结构里：它是「本题有效作答人数」，
 * 既是选择题占比的分母，也是文本题/评分题的展示值，各处都必须同口径。
 */
@Data
public class QuestionStatsVO {

    private Long questionId;

    private int sortNo;

    private String questionType;

    private String title;

    private String description;

    /** 本题的有效作答人数（口径：有效答卷 && 该题有作答） */
    private long answeredCount;

    /** 本章数据来自多少份有效答卷；同一次查询里所有题都一样，放在每项上是为了前端单题渲染时不用再取上下文 */
    private long validSheetCount;

    /** 选择题：各选项人数与占比（FR-STAT-02） */
    private List<OptionStatsVO> options;

    /** 文本题：作答列表与词频（FR-STAT-03） */
    private TextStatsVO text;

    /** 评分题：平均分与星级分布（FR-STAT-04） */
    private RatingStatsVO rating;
}
