package com.yjh.backend1.module.stat.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yjh.backend1.common.BizException;
import com.yjh.backend1.module.answer.entity.AnswerItem;
import com.yjh.backend1.module.answer.entity.AnswerSheet;
import com.yjh.backend1.module.answer.mapper.AnswerItemMapper;
import com.yjh.backend1.module.answer.mapper.AnswerSheetMapper;
import com.yjh.backend1.module.stat.dto.OptionStatsVO;
import com.yjh.backend1.module.stat.dto.QuestionStatsVO;
import com.yjh.backend1.module.stat.dto.RatingBucketVO;
import com.yjh.backend1.module.stat.dto.RatingStatsVO;
import com.yjh.backend1.module.stat.dto.SurveyStatsVO;
import com.yjh.backend1.module.stat.dto.TextAnswerVO;
import com.yjh.backend1.module.stat.dto.TextStatsVO;
import com.yjh.backend1.module.stat.dto.WordFreqVO;
import com.yjh.backend1.module.survey.entity.Survey;
import com.yjh.backend1.module.survey.entity.SurveyOption;
import com.yjh.backend1.module.survey.entity.SurveyQuestion;
import com.yjh.backend1.module.survey.enums.QuestionType;
import com.yjh.backend1.module.survey.enums.SurveyCategory;
import com.yjh.backend1.module.survey.service.SurveyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 统计服务（FR-STAT）。
 * <p>
 * <b>口径</b>：全部数字只用「有效答卷」（{@code answer_sheet.is_valid = 1} 且未删除），
 * 这是 FR-STAT-06 的硬要求。分母统一是该题的有效作答人数，不是回收总数 ——
 * 有人跳过非必答题时，题目占比相加才等于 100%。
 * <p>
 * <b>为什么在内存里聚合而不是写 GROUP BY</b>：
 * 选项答案存在 {@code answer_item.option_values} 这个 JSON 列里，SQL 侧聚合要用
 * {@code JSON_CONTAINS} 逐选项扫一遍，十个选项就是十次全表扫描；而这里一次查询把
 * 「某问卷的全部答案明细」取回来，几百份答卷几千行，内存聚合更快也更不容易写错。
 * 数据量真长起来时再换 SQL，接口签名不用动。
 * <p>
 * <b>不做预聚合表</b>（R-STAT-1）：课程级数据量下，按需查询的响应远低于 1 秒；
 * 预聚合会引入「答卷变了、统计没变」的不一致窗口，得不偿失。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StatService {

    private final SurveyService surveyService;
    private final AnswerSheetMapper sheetMapper;
    private final AnswerItemMapper itemMapper;

    /** 文本作答列表最多返回多少条，超出标记 truncated */
    private static final int TEXT_LIST_LIMIT = 200;

    /** 词频最多返回多少个词 */
    private static final int WORD_LIMIT = 20;

    /** 高频词的最低出现次数，低于此值视为噪音 */
    private static final int WORD_MIN_COUNT = 2;

    /** 停用词：中文虚词 + 常见无意义词，人工维护，够用即可 */
    private static final Set<String> STOP_WORDS = Set.of(
            "这个", "那个", "我们", "你们", "他们", "自己", "什么", "怎么", "可以", "没有",
            "就是", "还是", "但是", "因为", "所以", "如果", "这样", "那样", "一个", "一些",
            "老师", "同学", "课程", "觉得", "希望", "建议", "感觉", "非常", "比较", "有点");

    /**
     * 整卷统计（FR-STAT-01）。
     * <p>
     * 权限：{@code stat:read:self} 只能看自己的问卷，{@code stat:read:all} 看全部。
     * 归属校验复用 {@link SurveyService#requireOwned}，与答卷列表同一套规则，
     * 避免出现「能看答卷但看不到统计」这种割裂的权限体验。
     */
    public SurveyStatsVO surveyStats(Long surveyId) {
        Survey s = surveyService.loadSurvey(surveyId);
        surveyService.requireOwned(s, "stat:read:self", "stat:read:all");

        List<AnswerSheet> sheets = validSheets(surveyId);
        Map<Long, LocalDateTime> submitTimeMap = new HashMap<>();
        for (AnswerSheet sheet : sheets) {
            submitTimeMap.put(sheet.getId(), sheet.getCreateTime());
        }

        List<SurveyQuestion> questions = surveyService.listQuestions(surveyId);
        Map<Long, List<SurveyOption>> optionMap = surveyService.listOptionsByQuestion(questions);
        List<AnswerItem> items = loadItems(surveyId);
        Map<Long, List<AnswerItem>> itemsByQuestion = groupByQuestion(items);

        SurveyStatsVO vo = new SurveyStatsVO();
        vo.setSurveyId(s.getId());
        vo.setSurveyTitle(s.getTitle());
        vo.setCategory(s.getCategory());
        vo.setCategoryLabel(SurveyCategory.of(s.getCategory()).getLabel());
        vo.setStatus(s.getStatus());
        vo.setAnonymous(s.anonymousEnabled());
        vo.setValidSheetCount(sheets.size());
        Long total = sheetMapper.selectCount(Wrappers.<AnswerSheet>lambdaQuery()
                .eq(AnswerSheet::getSurveyId, surveyId));
        vo.setTotalSheetCount(total == null ? 0L : total);
        vo.setPublishedTime(s.getPublishedTime());
        vo.setGeneratedAt(LocalDateTime.now());

        List<QuestionStatsVO> qs = new ArrayList<>(questions.size());
        for (SurveyQuestion q : questions) {
            qs.add(questionStats(q, optionMap.getOrDefault(q.getId(), List.of()),
                    itemsByQuestion.getOrDefault(q.getId(), List.of()),
                    submitTimeMap, sheets.size()));
        }
        vo.setQuestions(qs);
        return vo;
    }

    /** 单题统计，供整卷接口内部复用；也允许单独取一题（前端刷新单图时用） */
    public QuestionStatsVO singleQuestionStats(Long surveyId, Long questionId) {
        Survey s = surveyService.loadSurvey(surveyId);
        surveyService.requireOwned(s, "stat:read:self", "stat:read:all");

        SurveyQuestion q = surveyService.listQuestions(surveyId).stream()
                .filter(x -> x.getId().equals(questionId))
                .findFirst()
                .orElseThrow(() -> BizException.notFound("题目不存在或已从该问卷移除"));

        Map<Long, List<SurveyOption>> optionMap = surveyService.listOptionsByQuestion(List.of(q));
        Map<Long, LocalDateTime> submitTimeMap = new HashMap<>();
        List<AnswerSheet> sheets = validSheets(surveyId);
        for (AnswerSheet sheet : sheets) {
            submitTimeMap.put(sheet.getId(), sheet.getCreateTime());
        }
        List<AnswerItem> items = loadItems(surveyId).stream()
                .filter(i -> questionId.equals(i.getQuestionId()))
                .toList();
        return questionStats(q, optionMap.getOrDefault(q.getId(), List.of()), items,
                submitTimeMap, sheets.size());
    }

    /* ------------------------------------------------------------------ */

    private List<AnswerSheet> validSheets(Long surveyId) {
        return sheetMapper.selectList(Wrappers.<AnswerSheet>lambdaQuery()
                .eq(AnswerSheet::getSurveyId, surveyId)
                .eq(AnswerSheet::getIsValid, 1)
                .orderByAsc(AnswerSheet::getId));
    }

    /**
     * 取本问卷的全部答案明细，只取有效答卷的。
     * <p>
     * 用 {@code inSql} 而不是先查 id 再拼 IN：答卷上限是学生人数，量不大，
     * 但拼一个几百个 id 的 IN 串既丑又有长度上限风险，交给数据库做子查询更省事。
     * {@code surveyId} 是 Long，直接拼进 SQL 无注入风险。
     */
    private List<AnswerItem> loadItems(Long surveyId) {
        String sub = "SELECT id FROM answer_sheet WHERE survey_id = " + surveyId
                + " AND is_valid = 1 AND deleted = 0";
        return itemMapper.selectList(Wrappers.<AnswerItem>lambdaQuery()
                .eq(AnswerItem::getSurveyId, surveyId)
                .inSql(AnswerItem::getSheetId, sub));
    }

    private static Map<Long, List<AnswerItem>> groupByQuestion(List<AnswerItem> items) {
        Map<Long, List<AnswerItem>> map = new LinkedHashMap<>();
        for (AnswerItem i : items) {
            map.computeIfAbsent(i.getQuestionId(), k -> new ArrayList<>()).add(i);
        }
        return map;
    }

    private QuestionStatsVO questionStats(SurveyQuestion q,
                                          List<SurveyOption> options,
                                          List<AnswerItem> items,
                                          Map<Long, LocalDateTime> submitTimeMap,
                                          long validSheetCount) {
        QuestionStatsVO vo = new QuestionStatsVO();
        vo.setQuestionId(q.getId());
        vo.setSortNo(q.getSortNo() == null ? 0 : q.getSortNo());
        vo.setQuestionType(q.getQuestionType());
        vo.setTitle(q.getTitle());
        vo.setDescription(q.getDescription());
        vo.setValidSheetCount(validSheetCount);

        QuestionType type = QuestionType.of(q.getQuestionType());
        if (type == null) {
            // 理论不可达：题型写入前已校验。真出现时给空壳而不抛异常，
            // 避免一道脏数据让整页统计打不开。
            vo.setAnsweredCount(0);
            return vo;
        }

        switch (type) {
            case SINGLE, MULTIPLE -> {
                List<AnswerItem> answered = items.stream()
                        .filter(i -> i.getOptionValues() != null && !i.getOptionValues().isEmpty())
                        .toList();
                vo.setAnsweredCount(answered.size());
                vo.setOptions(optionStats(options, answered));
            }
            case TEXT, TEXTAREA -> {
                List<AnswerItem> answered = items.stream()
                        .filter(i -> i.getTextValue() != null && !i.getTextValue().isBlank())
                        .toList();
                vo.setAnsweredCount(answered.size());
                vo.setText(textStats(answered, submitTimeMap));
            }
            case RATING -> {
                List<AnswerItem> answered = items.stream()
                        .filter(i -> i.getNumberValue() != null)
                        .toList();
                vo.setAnsweredCount(answered.size());
                vo.setRating(ratingStats(q, answered));
            }
        }
        return vo;
    }

    private List<OptionStatsVO> optionStats(List<SurveyOption> options, List<AnswerItem> answered) {
        long base = answered.size();
        Map<Long, Long> counter = new HashMap<>();
        for (AnswerItem i : answered) {
            // 同一份答卷里重复选了同一个选项只算一次，前端不该出现重复，
            // 但脏数据不该让占比超过 100%
            for (Long oid : new HashSet<>(i.getOptionValues())) {
                counter.merge(oid, 1L, Long::sum);
            }
        }
        List<OptionStatsVO> list = new ArrayList<>(options.size());
        for (SurveyOption o : options) {
            long c = counter.getOrDefault(o.getId(), 0L);
            OptionStatsVO ov = new OptionStatsVO();
            ov.setOptionId(o.getId());
            ov.setContent(o.getContent());
            ov.setCount(c);
            ov.setRatio(base == 0 ? 0d : round4((double) c / base));
            list.add(ov);
        }
        return list;
    }

    private TextStatsVO textStats(List<AnswerItem> answered, Map<Long, LocalDateTime> submitTimeMap) {
        TextStatsVO vo = new TextStatsVO();
        vo.setAnsweredCount(answered.size());

        List<TextAnswerVO> sorted = new ArrayList<>(answered.size());
        for (AnswerItem i : answered) {
            sorted.add(new TextAnswerVO(i.getTextValue(), submitTimeMap.get(i.getSheetId())));
        }
        // 按提交时间倒序；时间缺失的排最后，避免 NPE
        sorted.sort(Comparator.comparing(TextAnswerVO::getSubmitTime,
                Comparator.nullsLast(Comparator.reverseOrder())));
        vo.setTruncated(sorted.size() > TEXT_LIST_LIMIT);
        vo.setAnswers(sorted.size() > TEXT_LIST_LIMIT
                ? new ArrayList<>(sorted.subList(0, TEXT_LIST_LIMIT))
                : sorted);

        vo.setWords(wordFrequency(sorted.stream().map(TextAnswerVO::getText).toList()));
        return vo;
    }

    /**
     * 简易词频（FR-STAT-03）。
     * <p>
     * 中文没有空格，正规做法是接分词库（jieba/ansj），那会把一个课程项目拖成
     * 「装依赖—调词典—处理版本冲突」的泥潭。这里用 2-gram：把连续汉字按两字一滑窗口切开，
     * 「教学质量很好」→ 教学/学质/质量/量很/很好。噪声不小，但高频词仍然能浮出来，
     * 对「一眼看出大家在说什么」这个目标是够的。
     * <p>
     * 连续英文/数字整体作为一个词，这样 "PPT" "2024" 不会被切碎。
     */
    private List<WordFreqVO> wordFrequency(List<String> texts) {
        Map<String, Long> counter = new HashMap<>();
        for (String text : texts) {
            if (text == null) {
                continue;
            }
            collectWords(text, counter);
        }
        return counter.entrySet().stream()
                .filter(e -> e.getValue() >= WORD_MIN_COUNT)
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed()
                        .thenComparing(Map.Entry.comparingByKey()))
                .limit(WORD_LIMIT)
                .map(e -> new WordFreqVO(e.getKey(), e.getValue()))
                .toList();
    }

    private static void collectWords(String text, Map<String, Long> counter) {
        StringBuilder cjk = new StringBuilder();
        StringBuilder latin = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (isCjk(c)) {
                flushLatin(latin, counter);
                cjk.append(c);
            } else if (Character.isLetterOrDigit(c)) {
                flushCjk(cjk, counter);
                latin.append(c);
            } else {
                flushCjk(cjk, counter);
                flushLatin(latin, counter);
            }
        }
        flushCjk(cjk, counter);
        flushLatin(latin, counter);
    }

    private static void flushCjk(StringBuilder sb, Map<String, Long> counter) {
        int n = sb.length();
        if (n == 1) {
            // 单个汉字信息量太低，不单独成词；但仍要清空缓冲
            sb.setLength(0);
            return;
        }
        for (int i = 0; i + 1 < n; i++) {
            String w = sb.substring(i, i + 2);
            if (!STOP_WORDS.contains(w)) {
                counter.merge(w, 1L, Long::sum);
            }
        }
        sb.setLength(0);
    }

    private static void flushLatin(StringBuilder sb, Map<String, Long> counter) {
        if (sb.length() >= 2) {
            counter.merge(sb.toString().toLowerCase(), 1L, Long::sum);
        }
        sb.setLength(0);
    }

    private static boolean isCjk(char c) {
        return c >= 0x4E00 && c <= 0x9FFF;
    }

    private RatingStatsVO ratingStats(SurveyQuestion q, List<AnswerItem> answered) {
        int max = q.getRatingMax() == null ? 5 : q.getRatingMax();
        long base = answered.size();
        Map<Integer, Long> counter = new HashMap<>();
        long sum = 0;
        for (AnswerItem i : answered) {
            int v = i.getNumberValue();
            counter.merge(v, 1L, Long::sum);
            sum += v;
        }

        RatingStatsVO vo = new RatingStatsVO();
        vo.setAnsweredCount(base);
        vo.setRatingMax(max);
        vo.setAverage(base == 0 ? 0d : Math.round((double) sum / base * 100) / 100d);

        List<RatingBucketVO> dist = new ArrayList<>(max);
        for (int star = 1; star <= max; star++) {
            long c = counter.getOrDefault(star, 0L);
            dist.add(new RatingBucketVO(star, c, base == 0 ? 0d : round4((double) c / base)));
        }
        vo.setDistribution(dist);
        return vo;
    }

    private static double round4(double v) {
        return Math.round(v * 10000d) / 10000d;
    }
}
