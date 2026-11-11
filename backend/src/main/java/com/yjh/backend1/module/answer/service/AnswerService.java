package com.yjh.backend1.module.answer.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yjh.backend1.common.BizException;
import com.yjh.backend1.common.PageResult;
import com.yjh.backend1.module.answer.dto.AnswerInput;
import com.yjh.backend1.module.answer.dto.AnswerQuery;
import com.yjh.backend1.module.answer.dto.AnswerSheetDetailVO;
import com.yjh.backend1.module.answer.dto.AnswerSheetVO;
import com.yjh.backend1.module.answer.dto.AnswerSummaryVO;
import com.yjh.backend1.module.answer.dto.FillStateVO;
import com.yjh.backend1.module.answer.dto.HallSurveyVO;
import com.yjh.backend1.module.answer.dto.MyAnswerVO;
import com.yjh.backend1.module.answer.dto.QuestionAnswerVO;
import com.yjh.backend1.module.answer.dto.SaveDraftRequest;
import com.yjh.backend1.module.answer.dto.SubmitAnswerRequest;
import com.yjh.backend1.module.answer.entity.AnswerItem;
import com.yjh.backend1.module.answer.entity.AnswerSheet;
import com.yjh.backend1.module.answer.entity.SurveyDraft;
import com.yjh.backend1.module.answer.mapper.AnswerItemMapper;
import com.yjh.backend1.module.answer.mapper.AnswerSheetMapper;
import com.yjh.backend1.module.answer.mapper.SurveyDraftMapper;
import com.yjh.backend1.module.log.service.OperationLogService;
import com.yjh.backend1.module.rbac.entity.SysRole;
import com.yjh.backend1.module.rbac.mapper.SysRoleMapper;
import com.yjh.backend1.module.survey.dto.QuestionVO;
import com.yjh.backend1.module.survey.dto.SurveyVO;
import com.yjh.backend1.module.survey.entity.Survey;
import com.yjh.backend1.module.survey.entity.SurveyOption;
import com.yjh.backend1.module.survey.entity.SurveyQuestion;
import com.yjh.backend1.module.survey.enums.QuestionType;
import com.yjh.backend1.module.survey.enums.SurveyStatus;
import com.yjh.backend1.module.survey.mapper.SurveyMapper;
import com.yjh.backend1.module.survey.service.SurveyService;
import com.yjh.backend1.module.user.entity.SysUser;
import com.yjh.backend1.module.user.mapper.SysUserMapper;
import com.yjh.backend1.security.LoginUser;
import com.yjh.backend1.security.UserContext;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 填写、草稿、答卷回收。
 * <p>
 * 这个模块有四条硬约束，改动前请先读完：
 *
 * <ol>
 *   <li><b>限填一次由数据库保证，不是靠代码判断</b>：
 *       {@code answer_sheet(survey_id, uniq_user)} 唯一索引 + 服务层预检查双保险。
 *       预检查只负责给出友好提示，真正的并发防线是索引。
 *       去掉索引只留预检查，压测时必然出现重复答卷。</li>
 *
 *   <li><b>匿名不是「前端不显示」</b>：匿名问卷照样写 {@code answer_sheet.user_id}
 *       （限填一次与「我的记录」要靠它），但任何面向发布者的出口——
 *       答卷列表、答卷详情——都必须走 {@link #toSheetVO} 把身份字段裁成 null。
 *       只要返回了值，开发者工具里就能看到，匿名承诺当场失效。</li>
 *
 *   <li><b>{@code survey.answer_count} 是「有效答卷数」</b>：
 *       提交 +1、标记无效 -1、恢复有效 +1、删除有效答卷 -1。
 *       四条路径必须成对修改，缺一条就会出现「列表显示 10 份、统计只有 8 份」。</li>
 *
 *   <li><b>数据级归属校验走 {@link SurveyService#requireOwned}</b>，
 *       全量标记统一用 {@code answer:read:survey:all}（仅 ADMIN 具备）。
 *       教师只能看/改自己问卷的答卷，判定顺序与问卷模块保持一致。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnswerService {

    private final AnswerSheetMapper sheetMapper;
    private final AnswerItemMapper itemMapper;
    private final SurveyDraftMapper draftMapper;
    private final SurveyMapper surveyMapper;
    private final SysUserMapper userMapper;
    private final SysRoleMapper roleMapper;
    private final SurveyService surveyService;
    private final AnswerWriteService answerWriteService;
    private final OperationLogService operationLogService;
    private final ObjectMapper objectMapper;

    /** 草稿 upsert 的死锁重试次数。3 次足够：死锁成因（锁顺序颠倒）每次都是瞬时的。 */
    private static final int DRAFT_UPSERT_RETRIES = 3;

    /** 「全量」判定所用的权限码：只有 ADMIN 具备 */
    private static final String ALL_PERM = "answer:read:survey:all";
    /** 「仅自己问卷」判定所用的权限码 */
    private static final String SELF_PERM = "answer:read:survey:self";

    // =========================================================
    // 问卷大厅
    // =========================================================

    /**
     * 问卷大厅：当前可填的问卷 + 我对每份问卷的状态。
     * <p>
     * 只列 {@code PUBLISHED} 且在时间窗内的（R-PUB-2）。已暂停/已结束的问卷
     * 不出现在大厅里 —— 学生没有渠道主动去找一份已停收的问卷。
     */
    public PageResult<HallSurveyVO> hall(int page, int size, String keyword, String category) {
        LocalDateTime now = LocalDateTime.now();
        LambdaQueryWrapper<Survey> w = Wrappers.<Survey>lambdaQuery()
                .eq(Survey::getStatus, SurveyStatus.PUBLISHED.name())
                .and(q -> q.isNull(Survey::getStartTime).or().le(Survey::getStartTime, now))
                .and(q -> q.isNull(Survey::getEndTime).or().ge(Survey::getEndTime, now));
        if (StringUtils.hasText(keyword)) {
            w.like(Survey::getTitle, keyword.trim());
        }
        if (StringUtils.hasText(category)) {
            w.eq(Survey::getCategory, category.trim());
        }
        w.orderByDesc(Survey::getPublishedTime).orderByDesc(Survey::getId);

        Page<Survey> p = surveyMapper.selectPage(new Page<>(normPage(page), normSize(size)), w);
        List<HallSurveyVO> list = toHallList(p.getRecords());
        return PageResult.of(list, p.getTotal(), p.getCurrent(), p.getSize());
    }

    private List<HallSurveyVO> toHallList(List<Survey> records) {
        if (records.isEmpty()) {
            return List.of();
        }
        Long uid = UserContext.requireUserId();

        // 一次性把这批问卷里「我的答卷」和「我的草稿」取回来，避免每张卡片两次查询
        List<Long> ids = records.stream().map(Survey::getId).toList();
        Map<Long, AnswerSheet> sheetMap = new HashMap<>();
        for (AnswerSheet s : sheetMapper.selectList(Wrappers.<AnswerSheet>lambdaQuery()
                .eq(AnswerSheet::getUserId, uid)
                .in(AnswerSheet::getSurveyId, ids))) {
            // 同一问卷可能有多份（限填一次=0 的问卷），取最早的一份作为「已提交」凭证
            sheetMap.merge(s.getSurveyId(), s, (a, b) -> a.getId() <= b.getId() ? a : b);
        }
        Map<Long, SurveyDraft> draftMap = new HashMap<>();
        for (SurveyDraft d : draftMapper.selectList(Wrappers.<SurveyDraft>lambdaQuery()
                .eq(SurveyDraft::getUserId, uid)
                .in(SurveyDraft::getSurveyId, ids))) {
            draftMap.put(d.getSurveyId(), d);
        }

        List<SurveyVO> base = surveyService.toVOList(records);
        List<HallSurveyVO> list = new ArrayList<>(records.size());
        for (int i = 0; i < records.size(); i++) {
            Survey s = records.get(i);
            HallSurveyVO vo = new HallSurveyVO();
            BeanUtils.copyProperties(base.get(i), vo);

            AnswerSheet sheet = sheetMap.get(s.getId());
            SurveyDraft draft = draftMap.get(s.getId());
            if (sheet != null) {
                vo.setMyStatus("SUBMITTED");
                vo.setSubmitTime(sheet.getCreateTime());
            } else if (draft != null) {
                vo.setMyStatus("DRAFT");
                vo.setDraftTime(draft.getUpdateTime());
            } else {
                vo.setMyStatus("NOT_STARTED");
            }
            list.add(vo);
        }
        return list;
    }

    // =========================================================
    // 进入填写页
    // =========================================================

    /**
     * 填写页的进入状态。前端据此决定渲染表单 / 已提交回执 / 不可填写提示。
     */
    public FillStateVO fillState(Long surveyId) {
        Survey s = surveyService.loadSurvey(surveyId);
        Long uid = UserContext.requireUserId();

        FillStateVO vo = new FillStateVO();
        vo.setSurveyId(s.getId());
        vo.setSurveyTitle(s.getTitle());
        vo.setSubmitted(false);

        AnswerSheet sheet = findMySheet(surveyId, uid);
        if (sheet != null && Integer.valueOf(1).equals(s.getLimitOnce())) {
            vo.setFillable(false);
            vo.setReason("ALREADY_SUBMITTED");
            vo.setSubmitted(true);
            vo.setSheetId(sheet.getId());
            vo.setSubmitTime(sheet.getCreateTime());
            return vo;
        }

        String reason = unfillableReason(s);
        if (reason != null) {
            vo.setFillable(false);
            vo.setReason(reason);
            return vo;
        }

        vo.setFillable(true);

        SurveyDraft draft = draftMapper.selectOne(Wrappers.<SurveyDraft>lambdaQuery()
                .eq(SurveyDraft::getSurveyId, surveyId)
                .eq(SurveyDraft::getUserId, uid)
                .last("LIMIT 1"));
        if (draft != null) {
            vo.setDraftAnswers(parseDraft(draft.getAnswersJson()));
            vo.setDraftTime(draft.getUpdateTime());
        }
        return vo;
    }

    /**
     * 判定问卷为什么不能填，返回机器可读的原因码，可填时返回 null。
     * <p>
     * 用原因码而不是直接给中文文案：前端可以按码做不同引导
     * （未开始 → 显示倒计时；已结束 → 显示「查看我的记录」），
     * 而不是把一段中文字符串再解析一遍。
     */
    private String unfillableReason(Survey s) {
        if (!SurveyStatus.PUBLISHED.name().equals(s.getStatus())) {
            SurveyStatus st = SurveyStatus.of(s.getStatus());
            if (st == SurveyStatus.PAUSED) {
                return "PAUSED";
            }
            if (st == SurveyStatus.CLOSED) {
                return "CLOSED";
            }
            return "CLOSED";
        }
        LocalDateTime now = LocalDateTime.now();
        if (s.getStartTime() != null && now.isBefore(s.getStartTime())) {
            return "NOT_STARTED";
        }
        if (s.getEndTime() != null && now.isAfter(s.getEndTime())) {
            return "EXPIRED";
        }
        return null;
    }

    // =========================================================
    // 草稿
    // =========================================================

    /** 读自己的草稿；没有草稿返回空数组 */
    public List<AnswerInput> getDraft(Long surveyId) {
        Survey s = surveyService.loadSurvey(surveyId);
        Long uid = UserContext.requireUserId();
        String reason = unfillableReason(s);
        if (reason != null) {
            throw BizException.badRequest(unfillableText(reason));
        }
        SurveyDraft d = draftMapper.selectOne(Wrappers.<SurveyDraft>lambdaQuery()
                .eq(SurveyDraft::getSurveyId, surveyId)
                .eq(SurveyDraft::getUserId, uid)
                .last("LIMIT 1"));
        return d == null ? List.of() : parseDraft(d.getAnswersJson());
    }

    /**
     * 保存草稿（upsert）。
     * <p>
     * 不做必答校验、不做长度校验之外的题型校验：草稿的语义就是「还没答完」，
     * 任何严格校验都会让用户丢掉已经输入的内容。
     * 但结构仍要干净 —— {@code questionId} 必须存在且属于该问卷，
     * 否则脏数据会一直堆在 JSON 里，提交时才发现已经没法追溯。
     * <p>
     * <b>刻意不加 {@code @Transactional}：</b>整个方法只有一条写语句（upsert）加一次回读，
     * 不需要事务。反过来，如果包了事务，遇到死锁时 MySQL 回滚的是<b>整个事务</b>，
     * 而 {@code upsertDraftWithRetry}
     * 是在语句级别重试的 —— 事务已被回滚，重试必然失败。
     * 不开事务时每条语句自动提交，语句级重试才真正成立。
     */
    public LocalDateTime saveDraft(Long surveyId, SaveDraftRequest req) {
        Survey s = surveyService.loadSurvey(surveyId);
        String reason = unfillableReason(s);
        if (reason != null) {
            throw BizException.badRequest(unfillableText(reason));
        }
        Long uid = UserContext.requireUserId();

        // 已提交且限填一次 → 不允许再存草稿，否则会出现「已提交还能改答案」的错觉
        if (Integer.valueOf(1).equals(s.getLimitOnce()) && findMySheet(surveyId, uid) != null) {
            throw BizException.conflict("你已经提交过该问卷，不能再保存草稿");
        }

        List<AnswerInput> answers = normalizeDraft(surveyId, req.getAnswers());
        String json;
        try {
            json = objectMapper.writeValueAsString(answers);
        } catch (Exception e) {
            throw BizException.badRequest("草稿内容无法序列化：" + e.getMessage());
        }

        // 单条原子 upsert：不先查存在与否，避免「两个请求都判断为不存在→都 insert→撞唯一键」。
        // 详见 SurveyDraftMapper.upsert 的注释（RR 隔离级别下读后写兜底修不干净）。
        //
        // 死锁重试：同一个人「刚提交完立刻又开始填下一份」时，提交流程的
        // DELETE survey_draft 与本条的 INSERT ... ON DUPLICATE KEY UPDATE
        // 会以相反顺序申请同一个唯一索引上的锁，InnoDB 判定死锁并回滚其中之一。
        // MySQL 的报错原文就是 "try restarting transaction"，重试是官方推荐的处置方式，
        // 而不是把它当 500 抛给用户 —— 用户什么都没做错，只是手快。
        // 重试逻辑放在 upsertDraftWithRetry：草稿 upsert 会与提交流程的 DELETE survey_draft
        // 在同一个唯一索引上加锁，一边成功一边被判死锁，MySQL 建议「restarting transaction」。
        upsertDraftWithRetry(surveyId, uid, json);
        SurveyDraft saved = draftMapper.selectOne(Wrappers.<SurveyDraft>lambdaQuery()
                .eq(SurveyDraft::getSurveyId, surveyId)
                .eq(SurveyDraft::getUserId, uid)
                .last("LIMIT 1"));
        return saved != null && saved.getUpdateTime() != null ? saved.getUpdateTime() : LocalDateTime.now();
    }

    /**
     * 提交成功后作废草稿。
     * <p>
     * 这一步刻意<b>不</b>放进提交事务，也不按 {@code survey_id + user_id} 条件删，
     * 而是先查出主键再按主键删 —— 两处都是为了不生间隙锁：
     * <ol>
     *   <li>{@code WHERE survey_id=? AND user_id=?} 用不上唯一索引的最左前缀（该索引是
     *       {@code (survey_id, user_id)} 上的唯一键，本来能用），但条件删除仍要扫索引区间，
     *       InnoDB 会对扫到的记录加 next-key 锁（记录锁 + 前面的间隙）。</li>
     *   <li>并发的草稿保存是 {@code INSERT ... ON DUPLICATE KEY UPDATE}，需要对同一段间隙
     *       取插入意向锁；意向锁与间隙锁互斥，于是两个事务互相等待 —— 这就是压测里
     *       反复出现的 {@code Deadlock found when trying to get lock}。</li>
     * </ol>
     * 按主键删只锁那一行，不碰间隙，与 upsert 不再交叉。
     * 查不到草稿是常态（大部分提交前没存过草稿），此时什么都不做。
     */
    private void cleanupDraft(Long surveyId, Long userId) {
        try {
            SurveyDraft draft = draftMapper.selectOne(Wrappers.<SurveyDraft>lambdaQuery()
                    .select(SurveyDraft::getId)
                    .eq(SurveyDraft::getSurveyId, surveyId)
                    .eq(SurveyDraft::getUserId, userId)
                    .last("LIMIT 1"));
            if (draft != null) {
                draftMapper.deleteById(draft.getId());
            }
        } catch (RuntimeException e) {
            // 草稿清理失败不影响提交结果：答卷已经落库，而残留草稿在下次进入填写页时
            // 会被「已提交」状态盖住（fillState 先看答卷再看草稿），且下次提交会再清一次
            log.warn("提交后清理草稿失败 surveyId={} userId={}: {}", surveyId, userId, e.toString());
        }
    }

    /**
     * 草稿 upsert + 死锁重试。
     * <p>
     * 场景：用户提交完一份问卷后立刻开始填下一份（压测里 70% 提交 + 10% 草稿会稳定复现）。
     * 提交流程在事务里 {@code DELETE FROM survey_draft WHERE survey_id=? AND user_id=?}，
     * 草稿保存在事务外对同一个唯一索引加锁；两边加锁顺序相反时 InnoDB 判定死锁，
     * 回滚其中一个并返回 {@code Deadlock found when trying to get lock; try restarting transaction}。
     * <p>
     * MySQL 的报错原文就建议「restarting transaction」—— 死锁是并发控制的正常产物，
     * 不是数据错误，重试即可。抛成 500 会让用户看到「服务器开小差」，而他什么都没做错，只是手快。
     * <p>
     * 捕获 {@link ConcurrencyFailureException} 而不只是 DeadlockLoserDataAccessException：
     * 锁等待超时（{@code Lock wait timeout exceeded}）也是同一类「稍后重试就好」的失败。
     * <p>
     * 之所以能在这里重试：{@code saveDraft} 刻意<b>没有</b> {@code @Transactional}，
     * 每条语句自带自动提交，语句级失败可以干净地再试一次。若包了事务，
     * 死锁回滚的是整个事务，在这里重试必然继续失败。
     */
    private void upsertDraftWithRetry(Long surveyId, Long uid, String json) {
        ConcurrencyFailureException last = null;
        for (int attempt = 1; attempt <= DRAFT_UPSERT_RETRIES; attempt++) {
            try {
                draftMapper.upsert(surveyId, uid, json);
                if (attempt > 1) {
                    log.info("草稿 upsert 第 {} 次重试成功 surveyId={} userId={}", attempt, surveyId, uid);
                }
                return;
            } catch (ConcurrencyFailureException e) {
                last = e;
                log.warn("草稿 upsert 并发冲突，第 {}/{} 次 surveyId={} userId={}: {}",
                        attempt, DRAFT_UPSERT_RETRIES, surveyId, uid, e.getClass().getSimpleName());
                if (attempt < DRAFT_UPSERT_RETRIES) {
                    try {
                        // 递增退避：死锁回滚后立刻重试容易再撞一次
                        Thread.sleep(5L * attempt);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw e;
                    }
                }
            }
        }
        throw last;
    }

    /**
     * 草稿清洗：只保留属于本问卷的题目，丢弃重复题号，文本按 maxLength 截断。
     * <p>
     * 草稿阶段刻意「宽容」：题目被删掉（问卷还在草稿态时改过结构）就默默丢弃对应答案，
     * 而不是报错。用户重进填写页看到的是一份干净的草稿，不需要理解为什么保存失败。
     */
    private List<AnswerInput> normalizeDraft(Long surveyId, List<AnswerInput> answers) {
        if (answers == null || answers.isEmpty()) {
            return List.of();
        }
        List<SurveyQuestion> questions = surveyService.listQuestions(surveyId);
        Map<Long, SurveyQuestion> qMap = new HashMap<>();
        for (SurveyQuestion q : questions) {
            qMap.put(q.getId(), q);
        }
        Map<Long, AnswerInput> kept = new LinkedHashMap<>();
        for (AnswerInput a : answers) {
            if (a == null || a.getQuestionId() == null) {
                continue;
            }
            SurveyQuestion q = qMap.get(a.getQuestionId());
            if (q == null || kept.containsKey(a.getQuestionId())) {
                continue;
            }
            AnswerInput clean = new AnswerInput();
            clean.setQuestionId(a.getQuestionId());
            clean.setOptionValues(a.getOptionValues());
            String text = a.getTextValue();
            if (text != null && q.getMaxLength() != null && text.length() > q.getMaxLength()) {
                text = text.substring(0, q.getMaxLength());
            }
            clean.setTextValue(text);
            clean.setNumberValue(a.getNumberValue());
            kept.put(clean.getQuestionId(), clean);
        }
        return new ArrayList<>(kept.values());
    }

    private List<AnswerInput> parseDraft(String json) {
        if (!StringUtils.hasText(json)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<AnswerInput>>() {
            });
        } catch (Exception e) {
            log.warn("草稿 JSON 解析失败，按空草稿处理: {}", e.getMessage());
            return List.of();
        }
    }

    // =========================================================
    // 提交
    // =========================================================

    /**
     * 提交答卷。
     * <p>
     * 幂等性由两层保证：先查一次「是否已提交」给出友好提示，
     * 再依赖唯一索引兜住并发（两个请求同时通过预检查时，第二条 insert 会撞索引）。
     * <p>
     * 匿名问卷提交后<b>不写操作日志</b>：日志里有操作人和问卷 ID，
     * 「某用户提交了某匿名问卷」本身就是可反推身份的信息，
     * 与权限矩阵第 5 节的匿名约束冲突。
     * <p>
     * <b>这里刻意不加 {@code @Transactional}：</b>写答卷与更新回收计数必须分成两个短事务，
     * 且顺序必须是「先提交答卷、再改计数」。原因见 {@link AnswerWriteService} 的类注释
     * ——100 并发压测时，把 {@code UPDATE survey SET answer_count} 放在写答卷的大事务里，
     * 会让同一份问卷的提交请求全部串行排队并耗尽连接池。
     */
    public AnswerSheetVO submit(Long surveyId, SubmitAnswerRequest req, HttpServletRequest httpReq) {
        Survey s = surveyService.loadSurvey(surveyId);
        String reason = unfillableReason(s);
        if (reason != null) {
            throw BizException.badRequest(unfillableText(reason));
        }
        Long uid = UserContext.requireUserId();

        boolean limitOnce = Integer.valueOf(1).equals(s.getLimitOnce());
        if (limitOnce && findMySheet(surveyId, uid) != null) {
            throw BizException.conflict("你已经提交过该问卷，不能重复填写");
        }

        List<SurveyQuestion> questions = surveyService.listQuestions(surveyId);
        if (questions.isEmpty()) {
            throw BizException.badRequest("该问卷还没有题目，无法提交");
        }
        Map<Long, List<SurveyOption>> optMap = surveyService.listOptionsByQuestion(questions);
        List<AnswerItem> items = validateAndBuild(surveyId, questions, optMap, req.getAnswers());

        AnswerSheet sheet;
        String logDetail = s.anonymousEnabled() ? null
                : "问卷《" + s.getTitle() + "》提交成功，作答 " + items.size() + " 题";
        try {
            // 一次事务里完成：答卷入库 + 回收计数 + 操作日志。
            // 为什么不拆成多个事务：每多一次 commit 就多等一次磁盘 fsync（本机约 6ms），
            // 100 并发下这个成本会直接把 P95 推过 3 秒。详见 AnswerWriteService 类注释。
            sheet = answerWriteService.commit(surveyId, uid, limitOnce, items, s.getTitle(), logDetail, httpReq);
        } catch (DuplicateKeyException e) {
            // 并发提交撞上唯一索引：说明另一次请求刚成功
            throw BizException.conflict("你已经提交过该问卷，不能重复填写");
        }

        // 作废草稿放在事务之外、且单独成语句。原因见 cleanupDraft 的注释：
        // 它在事务里会和并发的草稿保存抢同一段索引间隙，把用户正常的「交完接着填下一份」变成死锁。
        cleanupDraft(surveyId, uid);

        AnswerSheetVO vo = new AnswerSheetVO();
        vo.setId(sheet.getId());
        vo.setSurveyId(surveyId);
        vo.setSurveyTitle(s.getTitle());
        vo.setAnonymous(s.anonymousEnabled());
        vo.setSubmitTime(sheet.getCreateTime() == null ? LocalDateTime.now() : sheet.getCreateTime());
        vo.setIsValid(1);
        // 即便非匿名也不在「提交回执」里回显填写人姓名 —— 提交者自己知道是谁，没必要多传一次
        return vo;
    }

    /**
     * 逐题校验并构造待写入的明细。
     * <p>
     * 校验顺序刻意是「先结构、后必答」：结构错误（题号不存在、选项不属于该题）
     * 是程序层面的错误，必答缺失是用户层面的疏漏，两者提示语应该不同。
     */
    private List<AnswerItem> validateAndBuild(Long surveyId,
                                              List<SurveyQuestion> questions,
                                              Map<Long, List<SurveyOption>> optMap,
                                              List<AnswerInput> answers) {
        Map<Long, AnswerInput> given = new LinkedHashMap<>();
        if (answers != null) {
            for (AnswerInput a : answers) {
                if (a == null || a.getQuestionId() == null) {
                    continue;
                }
                if (given.containsKey(a.getQuestionId())) {
                    throw BizException.badRequest("同一道题提交了多份作答：" + a.getQuestionId());
                }
                given.put(a.getQuestionId(), a);
            }
        }

        Set<Long> validQids = new HashSet<>();
        for (SurveyQuestion q : questions) {
            validQids.add(q.getId());
        }
        for (Long qid : given.keySet()) {
            if (!validQids.contains(qid)) {
                throw BizException.badRequest("题目 " + qid + " 不属于该问卷");
            }
        }

        List<AnswerItem> items = new ArrayList<>();
        List<String> missing = new ArrayList<>();

        for (SurveyQuestion q : questions) {
            AnswerInput a = given.get(q.getId());
            QuestionType type = QuestionType.of(q.getQuestionType());
            if (type == null) {
                throw BizException.badRequest("题目 " + q.getId() + " 的题型不受支持：" + q.getQuestionType());
            }
            String label = shortTitle(q.getTitle(), q.getSortNo());

            boolean empty = isEmptyAnswer(a);
            if (empty) {
                if (q.required()) {
                    missing.add(label);
                }
                continue;
            }

            AnswerItem it = new AnswerItem();
            it.setQuestionId(q.getId());
            switch (type) {
                case SINGLE -> {
                    List<Long> vals = a.getOptionValues();
                    if (vals == null || vals.size() != 1) {
                        throw BizException.badRequest(label + " 是单选题，必须且只能选择 1 项");
                    }
                    it.setOptionValues(List.of(checkOption(q, optMap, vals.get(0), label)));
                }
                case MULTIPLE -> {
                    List<Long> vals = a.getOptionValues();
                    if (vals == null || vals.isEmpty()) {
                        throw BizException.badRequest(label + " 是多选题，至少选择 1 项");
                    }
                    Set<Long> uniq = new HashSet<>();
                    List<Long> normalized = new ArrayList<>(vals.size());
                    for (Long v : vals) {
                        checkOption(q, optMap, v, label);
                        if (!uniq.add(v)) {
                            throw BizException.badRequest(label + " 的选项重复提交：" + v);
                        }
                        normalized.add(v);
                    }
                    normalized.sort(Long::compareTo);
                    it.setOptionValues(normalized);
                }
                case TEXT, TEXTAREA -> {
                    String text = a.getTextValue() == null ? "" : a.getTextValue().trim();
                    int max = q.getMaxLength() == null ? 2000 : q.getMaxLength();
                    if (text.length() > max) {
                        throw BizException.badRequest(label + " 的作答超过 " + max + " 字上限（当前 " + text.length() + " 字）");
                    }
                    it.setTextValue(text);
                }
                case RATING -> {
                    Integer n = a.getNumberValue();
                    int max = q.getRatingMax() == null ? 5 : q.getRatingMax();
                    if (n == null) {
                        throw BizException.badRequest(label + " 是评分题，必须给出分值");
                    }
                    if (n < 1 || n > max) {
                        throw BizException.badRequest(label + " 的评分必须在 1~" + max + " 之间（当前 " + n + "）");
                    }
                    it.setNumberValue(n);
                }
                default -> throw BizException.badRequest(label + " 的题型不受支持");
            }
            items.add(it);
        }

        if (!missing.isEmpty()) {
            String head = missing.size() <= 3
                    ? String.join("、", missing)
                    : String.join("、", missing.subList(0, 3)) + " 等 " + missing.size() + " 道题";
            throw BizException.badRequest("还有必答题未作答：" + head);
        }

        if (items.isEmpty()) {
            throw BizException.badRequest("至少回答一道题才能提交");
        }
        return items;
    }

    private Long checkOption(SurveyQuestion q, Map<Long, List<SurveyOption>> optMap, Long optId, String label) {
        if (optId == null) {
            throw BizException.badRequest(label + " 的选项不能为空");
        }
        for (SurveyOption o : optMap.getOrDefault(q.getId(), List.of())) {
            if (Objects.equals(o.getId(), optId)) {
                return optId;
            }
        }
        throw BizException.badRequest(label + " 的选项 " + optId + " 不属于该题");
    }

    private static boolean isEmptyAnswer(AnswerInput a) {
        if (a == null) {
            return true;
        }
        boolean noOption = a.getOptionValues() == null || a.getOptionValues().isEmpty();
        boolean noText = !StringUtils.hasText(a.getTextValue());
        boolean noNumber = a.getNumberValue() == null;
        return noOption && noText && noNumber;
    }

    private static String shortTitle(String title, Integer sortNo) {
        String t = title == null ? "" : title.trim();
        if (t.length() > 14) {
            t = t.substring(0, 14) + "…";
        }
        return "第 " + (sortNo == null ? "?" : sortNo) + " 题「" + t + "」";
    }

    // =========================================================
    // 我的填写记录
    // =========================================================

    /** 我的填写记录（FR-FILL-07） */
    public PageResult<MyAnswerVO> myRecords(Integer isValid, int page, int size) {
        Long uid = UserContext.requireUserId();
        LambdaQueryWrapper<AnswerSheet> w = Wrappers.<AnswerSheet>lambdaQuery()
                .eq(AnswerSheet::getUserId, uid);
        if (isValid != null) {
            w.eq(AnswerSheet::getIsValid, isValid);
        }
        w.orderByDesc(AnswerSheet::getId);

        Page<AnswerSheet> p = sheetMapper.selectPage(new Page<>(normPage(page), normSize(size)), w);
        List<AnswerSheet> records = p.getRecords();
        if (records.isEmpty()) {
            return PageResult.of(List.of(), p.getTotal(), p.getCurrent(), p.getSize());
        }
        Map<Long, Survey> surveyMap = loadSurveys(records.stream().map(AnswerSheet::getSurveyId).toList());

        List<MyAnswerVO> list = new ArrayList<>(records.size());
        for (AnswerSheet s : records) {
            Survey sv = surveyMap.get(s.getSurveyId());
            MyAnswerVO vo = new MyAnswerVO();
            vo.setSheetId(s.getId());
            vo.setSurveyId(s.getSurveyId());
            vo.setSurveyTitle(sv == null ? "（问卷已删除）" : sv.getTitle());
            vo.setSurveyStatus(sv == null ? null : sv.getStatus());
            vo.setSubmitTime(s.getCreateTime());
            vo.setIsValid(s.getIsValid());
            vo.setContentVisible(sv != null && !sv.anonymousEnabled());
            list.add(vo);
        }
        return PageResult.of(list, p.getTotal(), p.getCurrent(), p.getSize());
    }

    /**
     * 我的某份答卷详情。
     * <p>
     * 匿名问卷返回<b>不含作答内容</b>（FR-FILL-08）：
     * 只给回执。「提交后还能翻回来看自己写了什么」听起来无害，
     * 但截图外传的成本极低，而匿名问卷（课程评价、意见征集）依赖的正是「不可追溯」。
     */
    public AnswerSheetDetailVO myDetail(Long sheetId) {
        Long uid = UserContext.requireUserId();
        AnswerSheet sheet = sheetMapper.selectById(sheetId);
        if (sheet == null || !Objects.equals(sheet.getUserId(), uid)) {
            throw BizException.notFound("答卷不存在");
        }
        Survey s = surveyService.loadSurvey(sheet.getSurveyId());
        AnswerSheetDetailVO vo = new AnswerSheetDetailVO();
        vo.setId(sheet.getId());
        vo.setSurveyId(s.getId());
        vo.setSurveyTitle(s.getTitle());
        vo.setAnonymous(s.anonymousEnabled());
        vo.setSubmitTime(sheet.getCreateTime());
        vo.setIsValid(sheet.getIsValid());
        vo.setInvalidReason(sheet.getInvalidReason());
        if (s.anonymousEnabled()) {
            vo.setItems(null);
            vo.setQuestions(null);
        } else {
            fillDetail(vo, s, sheet);
        }
        return vo;
    }

    // =========================================================
    // 答卷管理（发布者视角）
    // =========================================================

    /** 答卷列表（FR-ANS-01） */
    public PageResult<AnswerSheetVO> pageAnswers(AnswerQuery q) {
        if (q.getSurveyId() == null) {
            throw BizException.badRequest("必须指定问卷 ID");
        }
        Survey s = surveyService.loadSurvey(q.getSurveyId());
        surveyService.requireOwned(s, SELF_PERM, ALL_PERM);

        LambdaQueryWrapper<AnswerSheet> w = Wrappers.<AnswerSheet>lambdaQuery()
                .eq(AnswerSheet::getSurveyId, q.getSurveyId());
        if (q.getIsValid() != null) {
            w.eq(AnswerSheet::getIsValid, q.getIsValid());
        }
        if ("submit_asc".equalsIgnoreCase(q.getOrder())) {
            w.orderByAsc(AnswerSheet::getCreateTime).orderByAsc(AnswerSheet::getId);
        } else {
            w.orderByDesc(AnswerSheet::getCreateTime).orderByDesc(AnswerSheet::getId);
        }

        Page<AnswerSheet> p = sheetMapper.selectPage(new Page<>(normPage(q.getPage()), normSize(q.getSize())), w);
        List<AnswerSheetVO> vos = toSheetVOList(p.getRecords(), s);
        return PageResult.of(vos, p.getTotal(), p.getCurrent(), p.getSize());
    }

    /**
     * 答卷详情（FR-ANS-02）。
     * <p>匿名问卷同样走 {@link #toSheetVO}，身份字段为 null。
     */
    public AnswerSheetDetailVO sheetDetail(Long sheetId) {
        AnswerSheet sheet = sheetMapper.selectById(sheetId);
        if (sheet == null) {
            throw BizException.notFound("答卷不存在");
        }
        Survey s = surveyService.loadSurvey(sheet.getSurveyId());
        surveyService.requireOwned(s, SELF_PERM, ALL_PERM);

        AnswerSheetDetailVO vo = new AnswerSheetDetailVO();
        BeanUtils.copyProperties(toSheetVO(sheet, s, null), vo);
        vo.setInvalidReason(sheet.getInvalidReason());
        fillDetail(vo, s, sheet);
        return vo;
    }

    /**
     * 回收概览（FR-ANS-05）。
     * <p>
     * 三次 count 走的是同一张表的三个不同条件，索引 {@code idx_survey_time} 能覆盖；
     * 为了拿到「有效/无效/总数」三个数，一次 group by 也可以，但分开查更直白，
     * 且这份问卷的答卷量在课程级场景下很小。
     */
    public AnswerSummaryVO summary(Long surveyId) {
        Survey s = surveyService.loadSurvey(surveyId);
        surveyService.requireOwned(s, SELF_PERM, ALL_PERM);

        long total = sheetMapper.selectCount(Wrappers.<AnswerSheet>lambdaQuery()
                .eq(AnswerSheet::getSurveyId, surveyId));
        long valid = sheetMapper.selectCount(Wrappers.<AnswerSheet>lambdaQuery()
                .eq(AnswerSheet::getSurveyId, surveyId)
                .eq(AnswerSheet::getIsValid, 1));
        long invalid = total - valid;

        AnswerSummaryVO vo = new AnswerSummaryVO();
        vo.setSurveyId(surveyId);
        vo.setSurveyTitle(s.getTitle());
        vo.setTotal(total);
        vo.setValidCount(valid);
        vo.setInvalidCount(invalid);
        vo.setAnonymous(s.anonymousEnabled());

        long expected = countExpectedFillers();
        vo.setExpectedCount(expected);
        vo.setCompletionRate(expected <= 0 ? 0d : round4((double) valid / expected));
        return vo;
    }

    /** 标记无效（FR-ANS-03），可恢复 */
    @Transactional
    public AnswerSheetVO invalidate(Long sheetId, String reason, HttpServletRequest httpReq) {
        AnswerSheet sheet = loadSheetWithOwnerCheck(sheetId, "answer:invalidate");
        if (!sheet.valid()) {
            throw BizException.conflict("该答卷已被标记为无效");
        }
        Survey s = surveyService.loadSurvey(sheet.getSurveyId());

        AnswerSheet up = new AnswerSheet();
        up.setId(sheetId);
        up.setIsValid(0);
        up.setInvalidReason(StringUtils.hasText(reason) ? cut(reason.trim(), 255) : "发布者标记无效");
        sheetMapper.updateById(up);
        bumpAnswerCount(sheet.getSurveyId(), -1);

        operationLogService.write("答卷管理", "标记无效", "answer_sheet", sheetId,
                "问卷《" + s.getTitle() + "》答卷 #" + sheetId + " 标记无效：" + up.getInvalidReason(), httpReq);

        sheet.setIsValid(0);
        sheet.setInvalidReason(up.getInvalidReason());
        return toSheetVO(sheet, s, null);
    }

    /** 恢复有效 */
    @Transactional
    public AnswerSheetVO restore(Long sheetId, HttpServletRequest httpReq) {
        AnswerSheet sheet = loadSheetWithOwnerCheck(sheetId, "answer:invalidate");
        if (sheet.valid()) {
            throw BizException.conflict("该答卷当前已是有效状态");
        }
        Survey s = surveyService.loadSurvey(sheet.getSurveyId());

        AnswerSheet up = new AnswerSheet();
        up.setId(sheetId);
        up.setIsValid(1);
        up.setInvalidReason("");
        sheetMapper.updateById(up);
        bumpAnswerCount(sheet.getSurveyId(), 1);

        operationLogService.write("答卷管理", "恢复有效", "answer_sheet", sheetId,
                "问卷《" + s.getTitle() + "》答卷 #" + sheetId + " 恢复有效", httpReq);

        sheet.setIsValid(1);
        sheet.setInvalidReason("");
        return toSheetVO(sheet, s, null);
    }

    /**
     * 删除单份答卷（FR-ANS-04，软删，仅 ADMIN）。
     * <p>
     * 明细行保留不删：一旦答卷行被误删，明细还在，可以从库里还原。
     * 统计与列表都靠 {@code answer_sheet.deleted=0} 过滤，不会把删掉的算进去。
     */
    @Transactional
    public void deleteSheet(Long sheetId, HttpServletRequest httpReq) {
        AnswerSheet sheet = sheetMapper.selectById(sheetId);
        if (sheet == null) {
            throw BizException.notFound("答卷不存在");
        }
        Survey s = surveyService.loadSurvey(sheet.getSurveyId());
        surveyService.requireOwned(s, "answer:delete", ALL_PERM);

        boolean wasValid = sheet.valid();
        sheetMapper.deleteById(sheetId);
        if (wasValid) {
            bumpAnswerCount(sheet.getSurveyId(), -1);
        }
        operationLogService.write("答卷管理", "删除答卷", "answer_sheet", sheetId,
                "问卷《" + s.getTitle() + "》删除答卷 #" + sheetId + (wasValid ? "（有效）" : "（原本无效）"), httpReq);
    }

    private AnswerSheet loadSheetWithOwnerCheck(Long sheetId, String selfPerm) {
        AnswerSheet sheet = sheetMapper.selectById(sheetId);
        if (sheet == null) {
            throw BizException.notFound("答卷不存在");
        }
        Survey s = surveyService.loadSurvey(sheet.getSurveyId());
        surveyService.requireOwned(s, selfPerm, ALL_PERM);
        return sheet;
    }

    // =========================================================
    // 组装与工具
    // =========================================================

    /**
     * 答卷头转 VO —— <b>匿名裁剪的唯一出口</b>。
     * <p>
     * 任何新的接口要返回答卷信息，都必须走这里。新增出口时不要自己 new VO 再 copyProperties，
     * 那会把 userId / 姓名原样带出去，而且很难在代码评审时被发现。
     */
    private AnswerSheetVO toSheetVO(AnswerSheet sheet, Survey s, Map<Long, SysUser> userMap) {
        AnswerSheetVO vo = new AnswerSheetVO();
        vo.setId(sheet.getId());
        vo.setSurveyId(sheet.getSurveyId());
        vo.setSurveyTitle(s.getTitle());
        vo.setAnonymous(s.anonymousEnabled());
        vo.setSubmitTime(sheet.getCreateTime());
        vo.setIsValid(sheet.getIsValid());
        vo.setInvalidReason(sheet.getInvalidReason());

        if (s.anonymousEnabled()) {
            // 匿名：身份字段一律 null。不是「前端不展示」，是压根不返回。
            vo.setUserId(null);
            vo.setFillerName(null);
            vo.setFillerUsername(null);
            return vo;
        }
        SysUser u = null;
        if (userMap != null) {
            u = userMap.get(sheet.getUserId());
        }
        if (u == null && sheet.getUserId() != null) {
            u = userMapper.selectById(sheet.getUserId());
        }
        vo.setUserId(sheet.getUserId());
        vo.setFillerName(u == null ? null : u.getRealName());
        vo.setFillerUsername(u == null ? null : u.getUsername());
        return vo;
    }

    private List<AnswerSheetVO> toSheetVOList(List<AnswerSheet> records, Survey s) {
        if (records.isEmpty()) {
            return List.of();
        }
        Map<Long, SysUser> userMap = new HashMap<>();
        if (!s.anonymousEnabled()) {
            Set<Long> uids = new HashSet<>();
            for (AnswerSheet a : records) {
                if (a.getUserId() != null) {
                    uids.add(a.getUserId());
                }
            }
            if (!uids.isEmpty()) {
                for (SysUser u : userMapper.selectBatchIds(uids)) {
                    userMap.put(u.getId(), u);
                }
            }
        }
        List<AnswerSheetVO> list = new ArrayList<>(records.size());
        for (AnswerSheet a : records) {
            list.add(toSheetVO(a, s, userMap));
        }
        return list;
    }

    /** 填充逐题作答：未作答的题也出现，前端显示「未作答」而不是留白 */
    private void fillDetail(AnswerSheetDetailVO vo, Survey s, AnswerSheet sheet) {
        List<SurveyQuestion> questions = surveyService.listQuestions(s.getId());
        Map<Long, List<SurveyOption>> optMap = surveyService.listOptionsByQuestion(questions);

        Map<Long, AnswerItem> itemMap = new HashMap<>();
        if (!questions.isEmpty()) {
            List<AnswerItem> items = itemMapper.selectList(Wrappers.<AnswerItem>lambdaQuery()
                    .eq(AnswerItem::getSheetId, sheet.getId()));
            for (AnswerItem it : items) {
                itemMap.put(it.getQuestionId(), it);
            }
        }

        List<QuestionAnswerVO> rows = new ArrayList<>(questions.size());
        for (SurveyQuestion q : questions) {
            QuestionAnswerVO r = new QuestionAnswerVO();
            r.setQuestionId(q.getId());
            r.setQuestionType(q.getQuestionType());
            r.setQuestionTitle(q.getTitle());
            AnswerItem it = itemMap.get(q.getId());
            if (it == null) {
                r.setAnswered(false);
            } else {
                r.setAnswered(true);
                r.setOptionValues(it.getOptionValues());
                r.setTextValue(it.getTextValue());
                r.setNumberValue(it.getNumberValue());
                if (it.getOptionValues() != null && !it.getOptionValues().isEmpty()) {
                    Map<Long, String> textById = new HashMap<>();
                    for (SurveyOption o : optMap.getOrDefault(q.getId(), List.of())) {
                        textById.put(o.getId(), o.getContent());
                    }
                    List<String> texts = new ArrayList<>();
                    for (Long id : it.getOptionValues()) {
                        texts.add(textById.getOrDefault(id, "（选项已删除）"));
                    }
                    r.setOptionTexts(texts);
                }
            }
            rows.add(r);
        }
        vo.setItems(rows);

        // 题干也一并返回，省前端再请求一次问卷详情
        List<QuestionVO> qvs = surveyService.toDetailVO(s).getQuestions();
        vo.setQuestions(qvs == null ? List.of() : qvs);
    }

    private AnswerSheet findMySheet(Long surveyId, Long uid) {
        return sheetMapper.selectOne(Wrappers.<AnswerSheet>lambdaQuery()
                .eq(AnswerSheet::getSurveyId, surveyId)
                .eq(AnswerSheet::getUserId, uid)
                .orderByAsc(AnswerSheet::getId)
                .last("LIMIT 1"));
    }

    private Map<Long, Survey> loadSurveys(List<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, Survey> map = new HashMap<>();
        // 用的是会带 deleted=0 条件的批量查询：问卷若被删除就查不到，
        // 调用方据此显示「（问卷已删除）」，这正是我们想要的效果。
        for (Survey s : surveyMapper.selectBatchIds(new HashSet<>(ids))) {
            map.put(s.getId(), s);
        }
        return map;
    }

    /**
     * 调整问卷的有效答卷计数（冗余列），只做增减，不做全量重算。
     * <p>
     * 转调 {@link AnswerWriteService#bumpAnswerCount}，让这条 UPDATE 落在独立的短事务里 ——
     * 它锁的是同一份问卷的同一行，不能跟在长事务后面（详见该类注释）。
     */
    private void bumpAnswerCount(Long surveyId, int delta) {
        answerWriteService.bumpAnswerCount(surveyId, delta);
    }

    /**
     * 完成率分母：启用状态的学生账号数。
     * <p>
     * 这是<b>近似口径</b>——本期没有「指定填答名单」功能，无法知道某份问卷该由谁填。
     * 接口文档里必须写明这一点，否则「完成率 40%」会被当成精确值。
     */
    private long countExpectedFillers() {
        SysRole student = roleMapper.selectOne(Wrappers.<SysRole>lambdaQuery()
                .eq(SysRole::getCode, SysRole.STUDENT)
                .last("LIMIT 1"));
        if (student == null) {
            return 0;
        }
        return userMapper.selectCount(Wrappers.<SysUser>lambdaQuery()
                .eq(SysUser::getStatus, 1)
                .inSql(SysUser::getId,
                        "SELECT user_id FROM sys_user_role WHERE role_id = " + student.getId()));
    }

    private static String unfillableText(String reason) {
        return switch (reason) {
            case "NOT_STARTED" -> "问卷尚未开始，请到开始时间后再来填写";
            case "PAUSED" -> "问卷已暂停回收，暂时无法填写";
            case "CLOSED" -> "问卷已结束，无法再填写";
            case "EXPIRED" -> "问卷已过截止时间，无法再填写";
            default -> "问卷当前不可填写";
        };
    }

    private static int normPage(Integer page) {
        return page == null || page < 1 ? 1 : page;
    }

    private static int normSize(Integer size) {
        if (size == null || size < 1) {
            return 20;
        }
        return Math.min(size, 100);
    }

    private static double round4(double v) {
        return Math.round(v * 10000d) / 10000d;
    }

    private static String cut(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
