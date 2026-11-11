package com.yjh.backend1.module.stat.dto;

import lombok.Data;

import java.util.List;

/**
 * 文本题的统计结果（FR-STAT-03）。
 * <p>
 * 文本题不做图表，只给「作答人数 + 条目 + 词频」三样东西：
 * 图表对自由文本没有意义，而词频能让人一眼看出高频反馈。
 * <p>
 * {@code words} 是简易分词的结果，不是 NLP：中文按 2-gram 切分，
 * 停用词与标点直接过滤。课程级数据量下够用，不必引入分词库。
 */
@Data
public class TextStatsVO {

    /** 该题的有效作答人数（非空文本条数） */
    private long answeredCount;

    /** 作答列表，按提交时间倒序，最多 {@code listLimit} 条 */
    private List<TextAnswerVO> answers;

    /** 列表被截断时为 true，前端提示「仅显示最近 N 条，完整内容请导出」 */
    private boolean truncated;

    /** 词频，按出现次数倒序，最多 20 个 */
    private List<WordFreqVO> words;
}
