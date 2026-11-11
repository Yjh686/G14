package com.yjh.backend1.module.export.service;

import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.ExcelWriter;
import com.alibaba.excel.write.metadata.WriteSheet;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yjh.backend1.module.answer.entity.AnswerItem;
import com.yjh.backend1.module.answer.entity.AnswerSheet;
import com.yjh.backend1.module.answer.mapper.AnswerItemMapper;
import com.yjh.backend1.module.answer.mapper.AnswerSheetMapper;
import com.yjh.backend1.module.log.service.OperationLogService;
import com.yjh.backend1.module.stat.dto.OptionStatsVO;
import com.yjh.backend1.module.stat.dto.QuestionStatsVO;
import com.yjh.backend1.module.stat.dto.RatingBucketVO;
import com.yjh.backend1.module.stat.dto.SurveyStatsVO;
import com.yjh.backend1.module.stat.service.StatService;
import com.yjh.backend1.module.survey.entity.Survey;
import com.yjh.backend1.module.survey.entity.SurveyOption;
import com.yjh.backend1.module.survey.entity.SurveyQuestion;
import com.yjh.backend1.module.survey.enums.QuestionType;
import com.yjh.backend1.module.survey.service.SurveyService;
import com.yjh.backend1.module.user.entity.SysUser;
import com.yjh.backend1.module.user.mapper.SysUserMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Excel 导出（FR-EXP）。
 * <p>
 * 两个 Sheet：
 * <ul>
 *   <li><b>明细</b>：一行一份答卷，一列一道题</li>
 *   <li><b>汇总</b>：每题各选项的人次与占比（选择题按选项拆行，评分题按星级拆行）</li>
 * </ul>
 * <p>
 * <b>流式写出（FR-EXP-03）</b>：答卷按页取（每页 {@value #PAGE_SIZE} 份），
 * 每页只把这一页的答案明细查回来，构造行后立即交给 EasyExcel 写盘，
 * 写完整页再取下一页。任何时刻内存里只有一页数据，与总答卷量无关。
 * 不能先 `selectList` 全量再 `doWrite` —— 那是「看起来用了 EasyExcel，实际还是全量加载」。
 * <p>
 * <b>匿名（R-EXP-1）</b>：匿名问卷不写「填写人」「学号」两列，
 * 连列头都不出现，而不是留空列。留空列会让人以为数据丢了。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExportService {

    private final SurveyService surveyService;
    private final StatService statService;
    private final AnswerSheetMapper sheetMapper;
    private final AnswerItemMapper itemMapper;
    private final SysUserMapper userMapper;
    private final OperationLogService logService;

    /** 每页取多少份答卷：太小则查询次数多，太大则内存峰值高 */
    private static final int PAGE_SIZE = 200;

    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private static final String CONTENT_TYPE =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    /**
     * 导出某份问卷的答卷。
     * <p>
     * 直接写 {@link HttpServletResponse} 的输出流，不生成临时文件：
     * 临时文件要管清理、要防并发同名，而这里的内容是一次性的。
     */
    public void exportSurvey(Long surveyId, HttpServletResponse response, HttpServletRequest request)
            throws IOException {
        Survey s = surveyService.loadSurvey(surveyId);
        surveyService.requireOwned(s, "export:self", "export:all");

        List<SurveyQuestion> questions = surveyService.listQuestions(surveyId);
        Map<Long, List<SurveyOption>> optionMap = surveyService.listOptionsByQuestion(questions);
        boolean anonymous = s.anonymousEnabled();

        // 列头：固定列 + 每题一列
        List<List<String>> head = buildDetailHead(questions, anonymous);
        // 汇总在写明细之前算好：它只依赖有效答卷，与明细的分页无关，
        // 但要在同一个 ExcelWriter 里先写明细再写汇总（Sheet 顺序固定）
        SurveyStatsVO stats = statService.surveyStats(surveyId);
        List<List<Object>> summaryRows = buildSummaryRows(stats);

        String filename = buildFilename(s.getTitle());
        prepareResponse(response, filename);

        try (ExcelWriter writer = EasyExcel.write(response.getOutputStream()).build()) {
            WriteSheet detail = EasyExcel.writerSheet(0, "明细").head(head).build();
            long total = writeDetailBatches(writer, detail, surveyId, questions, optionMap, anonymous);
            if (total == 0) {
                // 没有答卷时也必须把「明细」这个 Sheet 建出来：
                // EasyExcel 只有在真正写数据时才会创建 Sheet，不补这一行，
                // 导出的文件里就只剩「汇总」，拿到文件的人会以为明细丢了。
                List<Object> placeholder = new ArrayList<>(head.size());
                placeholder.add("暂无答卷");
                for (int i = 1; i < head.size(); i++) {
                    placeholder.add("");
                }
                writer.write(List.of(placeholder), detail);
            }
            WriteSheet summary = EasyExcel.writerSheet(1, "汇总")
                    .head(buildSummaryHead())
                    .build();
            writer.write(summaryRows, summary);
            log.info("导出完成 surveyId={} 明细 {} 行 汇总 {} 行", surveyId, total, summaryRows.size());
        }

        logService.write("数据导出", "导出答卷", "survey", surveyId,
                "导出问卷《" + s.getTitle() + "》，文件 " + filename, request);
    }

    /* ------------------------------------------------------------------ */

    private List<List<String>> buildDetailHead(List<SurveyQuestion> questions, boolean anonymous) {
        List<List<String>> head = new ArrayList<>();
        head.add(List.of("序号"));
        head.add(List.of("提交时间"));
        head.add(List.of("答卷状态"));
        if (!anonymous) {
            head.add(List.of("填写人"));
            head.add(List.of("学号/工号"));
        }
        for (int i = 0; i < questions.size(); i++) {
            SurveyQuestion q = questions.get(i);
            head.add(List.of("第" + (i + 1) + "题 " + q.getTitle()));
        }
        return head;
    }

    private List<List<String>> buildSummaryHead() {
        List<List<String>> head = new ArrayList<>();
        head.add(List.of("题号"));
        head.add(List.of("题目"));
        head.add(List.of("题型"));
        head.add(List.of("项目"));
        head.add(List.of("人数"));
        head.add(List.of("占比"));
        return head;
    }

    /**
     * 分页写明细，返回写出的行数。
     * <p>
     * 每页先取答卷头，再按这批 sheetId 一次性取答案明细（避免 N+1），
     * 构造完行就交给 writer，本页的对象下一轮即可被回收。
     */
    private long writeDetailBatches(ExcelWriter writer, WriteSheet sheet, Long surveyId,
                                    List<SurveyQuestion> questions,
                                    Map<Long, List<SurveyOption>> optionMap,
                                    boolean anonymous) {
        // 选项 ID → 文案，导出时把 ID 换成人看得懂的内容
        Map<Long, String> optionText = new HashMap<>();
        for (List<SurveyOption> opts : optionMap.values()) {
            for (SurveyOption o : opts) {
                optionText.put(o.getId(), o.getContent());
            }
        }

        long written = 0;
        long pageNo = 1;
        while (true) {
            IPage<AnswerSheet> page = sheetMapper.selectPage(new Page<>(pageNo, PAGE_SIZE),
                    Wrappers.<AnswerSheet>lambdaQuery()
                            .eq(AnswerSheet::getSurveyId, surveyId)
                            .orderByAsc(AnswerSheet::getId));
            List<AnswerSheet> sheets = page.getRecords();
            if (sheets.isEmpty()) {
                break;
            }

            List<Long> sheetIds = sheets.stream().map(AnswerSheet::getId).toList();
            Map<Long, List<AnswerItem>> itemsBySheet = loadItemsBySheet(sheetIds);
            Map<Long, SysUser> userMap = anonymous ? Map.of() : loadUsers(sheets);

            List<List<Object>> rows = new ArrayList<>(sheets.size());
            for (AnswerSheet sh : sheets) {
                List<AnswerItem> items = itemsBySheet.getOrDefault(sh.getId(), List.of());
                Map<Long, AnswerItem> byQuestion = new HashMap<>();
                for (AnswerItem it : items) {
                    byQuestion.put(it.getQuestionId(), it);
                }

                List<Object> row = new ArrayList<>(questions.size() + 5);
                row.add(++written);
                row.add(sh.getCreateTime() == null ? "" : sh.getCreateTime().toString().replace('T', ' '));
                row.add(Integer.valueOf(1).equals(sh.getIsValid()) ? "有效" : "无效");
                if (!anonymous) {
                    SysUser u = sh.getUserId() == null ? null : userMap.get(sh.getUserId());
                    row.add(u == null ? "" : nullToEmpty(u.getRealName()));
                    row.add(u == null ? "" : nullToEmpty(u.getUsername()));
                }
                for (SurveyQuestion q : questions) {
                    row.add(cellValue(byQuestion.get(q.getId()), optionText));
                }
                rows.add(row);
            }

            writer.write(rows, sheet);
            if (pageNo * PAGE_SIZE >= page.getTotal()) {
                break;
            }
            pageNo++;
        }
        return written;
    }

    private Map<Long, List<AnswerItem>> loadItemsBySheet(List<Long> sheetIds) {
        List<AnswerItem> items = itemMapper.selectList(Wrappers.<AnswerItem>lambdaQuery()
                .in(AnswerItem::getSheetId, sheetIds));
        Map<Long, List<AnswerItem>> map = new HashMap<>();
        for (AnswerItem it : items) {
            map.computeIfAbsent(it.getSheetId(), k -> new ArrayList<>()).add(it);
        }
        return map;
    }

    private Map<Long, SysUser> loadUsers(List<AnswerSheet> sheets) {
        Set<Long> ids = new HashSet<>();
        for (AnswerSheet s : sheets) {
            if (s.getUserId() != null) {
                ids.add(s.getUserId());
            }
        }
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, SysUser> map = new HashMap<>();
        for (SysUser u : userMapper.selectBatchIds(ids)) {
            map.put(u.getId(), u);
        }
        return map;
    }

    /**
     * 单元格取值：三种题型统一成字符串。
     * <p>
     * 多选用「、」连接；不拆成多列 —— 拆列的前提是知道每题有几个选项，
     * 而那会让列头变成「第2题-选项A」这种没人看得懂的东西。
     */
    private String cellValue(AnswerItem item, Map<Long, String> optionText) {
        if (item == null) {
            return "";
        }
        if (item.getOptionValues() != null && !item.getOptionValues().isEmpty()) {
            List<String> texts = new ArrayList<>(item.getOptionValues().size());
            for (Long id : item.getOptionValues()) {
                texts.add(optionText.getOrDefault(id, "#" + id));
            }
            return String.join("、", texts);
        }
        if (item.getTextValue() != null) {
            return item.getTextValue();
        }
        if (item.getNumberValue() != null) {
            return String.valueOf(item.getNumberValue());
        }
        return "";
    }

    /**
     * 汇总行：选择题按选项拆行，评分题按星级拆行并追加一行平均分，文本题只给作答条数。
     * <p>
     * 占比写成百分比字符串（如 "42.86%"）而不是 0.4286 的数值：
     * 数值在 Excel 里默认右对齐且看不出是比例，加 % 更不容易被误读。
     */
    private List<List<Object>> buildSummaryRows(SurveyStatsVO stats) {
        List<List<Object>> rows = new ArrayList<>();
        List<QuestionStatsVO> qs = stats.getQuestions();
        for (int i = 0; i < qs.size(); i++) {
            QuestionStatsVO q = qs.get(i);
            String no = "第" + (i + 1) + "题";
            String type = typeLabel(q.getQuestionType());

            switch (QuestionType.of(q.getQuestionType())) {
                case SINGLE, MULTIPLE -> {
                    if (q.getOptions() == null) {
                        rows.add(row(no, q.getTitle(), type, "（无选项）", 0, "0%"));
                        break;
                    }
                    for (OptionStatsVO o : q.getOptions()) {
                        rows.add(row(no, q.getTitle(), type, o.getContent(), o.getCount(), pct(o.getRatio())));
                    }
                    rows.add(row(no, q.getTitle(), type, "【有效作答人数】", q.getAnsweredCount(), ""));
                }
                case RATING -> {
                    if (q.getRating() != null) {
                        for (RatingBucketVO b : q.getRating().getDistribution()) {
                            rows.add(row(no, q.getTitle(), type, b.getStar() + " 星",
                                    b.getCount(), pct(b.getRatio())));
                        }
                        rows.add(row(no, q.getTitle(), type,
                                String.format("【平均分 %.1f，参评 %d 人】",
                                        q.getRating().getAverage(), q.getRating().getAnsweredCount()),
                                q.getAnsweredCount(), ""));
                    }
                }
                case TEXT, TEXTAREA -> {
                    rows.add(row(no, q.getTitle(), type, "【有效作答条数】",
                            q.getAnsweredCount(), ""));
                    if (q.getText() != null && q.getText().getWords() != null) {
                        for (var w : q.getText().getWords()) {
                            rows.add(row(no, q.getTitle(), type,
                                    "高频词：" + w.getWord(), w.getCount(), ""));
                        }
                    }
                }
            }
        }
        if (rows.isEmpty()) {
            rows.add(row("—", "该问卷暂无题目", "—", "—", 0, ""));
        }
        // 末尾补一行口径说明，避免拿到文件的人把「回收总数」当分母
        rows.add(row("口径", "仅统计有效答卷", "—",
                "有效 " + stats.getValidSheetCount() + " 份 / 回收 " + stats.getTotalSheetCount() + " 份",
                stats.getValidSheetCount(), ""));
        return rows;
    }

    private static List<Object> row(Object... cells) {
        List<Object> r = new ArrayList<>(cells.length);
        for (Object c : cells) {
            r.add(c);
        }
        return r;
    }

    private static String pct(double ratio) {
        return String.format("%.2f%%", ratio * 100);
    }

    private static String typeLabel(String code) {
        QuestionType t = QuestionType.of(code);
        if (t == null) {
            return code;
        }
        return switch (t) {
            case SINGLE -> "单选";
            case MULTIPLE -> "多选";
            case TEXT -> "填空";
            case TEXTAREA -> "多行文本";
            case RATING -> "评分";
        };
    }

    /* ------------------------------------------------------------------ */

    /**
     * 文件名：{@code 问卷标题_导出时间.xlsx}。
     * <p>
     * 标题可能含 `/`、`:`、`*` 等文件名非法字符，统一替换成 `_`；
     * 长度截断到 60 字符，避免超过某些文件系统的单段上限。
     */
    private String buildFilename(String title) {
        String safe = title == null ? "问卷" : title.replaceAll("[\\\\/:*?\"<>|\\r\\n]", "_").trim();
        if (safe.isEmpty()) {
            safe = "问卷";
        }
        if (safe.length() > 60) {
            safe = safe.substring(0, 60);
        }
        return safe + "_" + LocalDateTime.now().format(FILE_TIME) + ".xlsx";
    }

    /**
     * 设置下载响应头。
     * <p>
     * 中文文件名必须同时给 {@code filename}（ASCII 兜底）和
     * {@code filename*}（RFC 5987，UTF-8 百分号编码）。
     * 只给前者，浏览器会显示成一串 `%E6%95%99...`；只给后者，老客户端拿不到名字。
     * <b>不要用 {@code setContentType} 带 charset</b>：xlsx 是二进制，带 charset 会让某些客户端尝试按文本解码。
     */
    private void prepareResponse(HttpServletResponse response, String filename) throws IOException {
        String encoded = URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20");
        response.reset();
        response.setContentType(CONTENT_TYPE);
        response.setCharacterEncoding(null);
        response.setHeader("Content-Disposition",
                "attachment; filename=\"export.xlsx\"; filename*=UTF-8''" + encoded);
        response.setHeader("Access-Control-Expose-Headers", "Content-Disposition");
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
