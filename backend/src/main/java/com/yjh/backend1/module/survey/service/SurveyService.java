package com.yjh.backend1.module.survey.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yjh.backend1.common.BizException;
import com.yjh.backend1.common.PageResult;
import com.yjh.backend1.module.log.service.OperationLogService;
import com.yjh.backend1.module.survey.dto.OptionSaveRequest;
import com.yjh.backend1.module.survey.dto.OptionVO;
import com.yjh.backend1.module.survey.dto.QuestionSaveRequest;
import com.yjh.backend1.module.survey.dto.QuestionVO;
import com.yjh.backend1.module.survey.dto.SurveyDetailVO;
import com.yjh.backend1.module.survey.dto.SurveyQuery;
import com.yjh.backend1.module.survey.dto.SurveySaveRequest;
import com.yjh.backend1.module.survey.dto.SurveyVO;
import com.yjh.backend1.module.survey.entity.Survey;
import com.yjh.backend1.module.survey.entity.SurveyOption;
import com.yjh.backend1.module.survey.entity.SurveyQuestion;
import com.yjh.backend1.module.survey.enums.QuestionType;
import com.yjh.backend1.module.survey.enums.SurveyCategory;
import com.yjh.backend1.module.survey.enums.SurveyStatus;
import com.yjh.backend1.module.survey.mapper.SurveyMapper;
import com.yjh.backend1.module.survey.mapper.SurveyOptionMapper;
import com.yjh.backend1.module.survey.mapper.SurveyQuestionMapper;
import com.yjh.backend1.module.user.entity.SysUser;
import com.yjh.backend1.module.user.mapper.SysUserMapper;
import com.yjh.backend1.security.LoginUser;
import com.yjh.backend1.security.UserContext;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 问卷创建与编辑、发布与分发。
 * <p>
 * 三条贯穿全类的硬约束：
 * <ul>
 *   <li><b>归属校验</b>：非 ADMIN 只能动自己创建的问卷，归属不符返回 403，不存在返回 404。
 *       校验统一走 {@link #requireOwned(Survey, String, String)}，不允许各接口自行判断。</li>
 *   <li><b>发布后锁定题目结构</b>：{@code saveQuestions} 只允许 DRAFT。
 *       已发布问卷改题型会让已收答卷与题目版本错位，统计随之失真。</li>
 *   <li><b>权限码 + 归属双校验</b>：权限码决定「能不能做这个动作」，
 *       归属决定「对哪条数据能做」。ADMIN 通过 {@code *:all} 绕过归属，但仍写操作日志。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SurveyService {

    private final SurveyMapper surveyMapper;
    private final SurveyQuestionMapper questionMapper;
    private final SurveyOptionMapper optionMapper;
    private final SysUserMapper userMapper;
    private final OperationLogService operationLogService;

    /** 分享码字符集：去掉 0/O/1/I/l 等易混字符，方便手抄与念读 */
    private static final char[] SHARE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();
    private static final int SHARE_CODE_LEN = 8;
    private static final SecureRandom RANDOM = new SecureRandom();

    private static final int MAX_QUESTION = 100;

    // =========================================================
    // 查询
    // =========================================================

    /**
     * 问卷分页列表。
     * <p>
     * {@code scope=all} 需要 {@code survey:list:all}；不带 scope 一律按「仅自己」处理。
     * 传了 all 但没权限 → 403，不静默降级成 mine（静默降级会让管理员以为看到了全量）。
     */
    public PageResult<SurveyVO> page(SurveyQuery q) {
        LoginUser u = UserContext.require();
        int page = q.getPage() == null || q.getPage() < 1 ? 1 : q.getPage();
        int size = q.getSize() == null || q.getSize() < 1 ? 20 : Math.min(q.getSize(), 100);

        boolean all = "all".equalsIgnoreCase(q.getScope());
        if (all && !u.hasPermission("survey:list:all")) {
            throw BizException.forbidden("您没有查看全部问卷的权限");
        }
        if (!all && !u.hasPermission("survey:list:self") && !u.hasPermission("survey:list:all")) {
            throw BizException.forbidden("您没有查看问卷列表的权限");
        }

        LambdaQueryWrapper<Survey> w = Wrappers.lambdaQuery();
        if (!all) {
            w.eq(Survey::getCreateBy, u.getUserId());
        }
        if (q.getKeyword() != null && !q.getKeyword().isBlank()) {
            w.like(Survey::getTitle, q.getKeyword().trim());
        }
        if (q.getStatus() != null && !q.getStatus().isBlank()) {
            SurveyStatus st = SurveyStatus.of(q.getStatus());
            if (st == null) {
                throw BizException.badRequest("未知的问卷状态：" + q.getStatus());
            }
            w.eq(Survey::getStatus, st.name());
        }
        if (q.getCategory() != null && !q.getCategory().isBlank()) {
            w.eq(Survey::getCategory, SurveyCategory.of(q.getCategory()).name());
        }
        w.orderByDesc(Survey::getId);

        Page<Survey> p = surveyMapper.selectPage(new Page<>(page, size), w);
        return PageResult.of(toVOList(p.getRecords()), p.getTotal(), p.getCurrent(), p.getSize());
    }

    /** 问卷详情（含题目），需要归属或全量读权限 */
    public SurveyDetailVO detail(Long id) {
        Survey s = requireOwned(loadSurvey(id), "survey:update:self", "survey:update:all");
        return toDetailVO(s);
    }

    /** 预览：结构与详情完全一致，单独一个接口只为语义清晰与权限码区分（survey:preview） */
    public SurveyDetailVO preview(Long id) {
        Survey s = requireOwned(loadSurvey(id), "survey:preview", "survey:preview");
        return toDetailVO(s);
    }

    /**
     * 按分享码取问卷（分享链接落地页用，FR-PUB-03）。
     * <p>
     * 不看归属：任何具备 survey:fill 的登录用户凭分享码都能取到结构，
     * 但会把创建人信息抹掉（填写者无需知道是谁建的，也避免向学生泄露教师列表）。
     * 已暂停/已结束/未到开始时间都照常返回结构，由前端按 fillable 展示提示，
     * 因为直接 404 会让填写者以为链接失效，反而增加询问成本。
     */
    public SurveyDetailVO detailByShareCode(String shareCode) {
        LoginUser u = UserContext.require();
        if (!u.hasPermission("survey:fill")) {
            throw BizException.forbidden("您没有填写问卷的权限");
        }
        String code = shareCode == null ? "" : shareCode.trim().toUpperCase();
        if (code.isEmpty()) {
            throw BizException.badRequest("分享码不能为空");
        }
        Survey s = surveyMapper.selectOne(Wrappers.<Survey>lambdaQuery()
                .eq(Survey::getShareCode, code));
        if (s == null) {
            throw BizException.notFound("分享码无效或问卷已被删除");
        }
        SurveyDetailVO vo = toDetailVO(s);
        vo.setCreateByName(null);
        return vo;
    }
    // =========================================================
    // 新建 / 修改 / 删除
    // =========================================================

    @Transactional(rollbackFor = Exception.class)
    public SurveyDetailVO create(SurveySaveRequest req, HttpServletRequest httpReq) {
        LoginUser u = UserContext.require();
        if (!u.hasPermission("survey:create")) {
            throw BizException.forbidden("您没有创建问卷的权限");
        }
        validateTimeWindow(req.getStartTime(), req.getEndTime());

        Survey s = new Survey();
        s.setTitle(req.getTitle().trim());
        s.setDescription(trimToNull(req.getDescription()));
        s.setCategory(SurveyCategory.of(req.getCategory()).name());
        s.setAnonymous(bool(req.getAnonymous(), false));
        s.setLimitOnce(bool(req.getLimitOnce(), true));
        s.setStartTime(req.getStartTime());
        s.setEndTime(req.getEndTime());
        s.setStatus(SurveyStatus.DRAFT.name());
        s.setQuestionCount(0);
        s.setAnswerCount(0);
        surveyMapper.insert(s);

        operationLogService.write("问卷管理", "新建问卷", "SURVEY", s.getId(),
                "标题=" + s.getTitle(), httpReq);
        return toDetailVO(s);
    }

    /**
     * 修改问卷元信息。
     * <p>
     * 已发布/已暂停/已结束的问卷只允许改标题、简介、截止时间。
     * 其余字段（匿名、限填、分类、开始时间）会被显式拒绝而不是静默忽略——
     * 静默忽略会让调用方以为改成功了，最终导致「我明明设成匿名了」这类扯皮。
     */
    @Transactional(rollbackFor = Exception.class)
    public SurveyDetailVO update(Long id, SurveySaveRequest req, HttpServletRequest httpReq) {
        Survey s = requireOwned(loadSurvey(id), "survey:update:self", "survey:update:all");
        boolean draft = SurveyStatus.DRAFT.name().equals(s.getStatus());

        if (!draft) {
            boolean anonymousChanged = !Objects.equals(s.getAnonymous(), bool(req.getAnonymous(), s.anonymousEnabled()));
            boolean limitChanged = !Objects.equals(s.getLimitOnce(), bool(req.getLimitOnce(), s.limitOnceEnabled()));
            boolean categoryChanged = !Objects.equals(s.getCategory(),
                    SurveyCategory.of(req.getCategory() == null ? s.getCategory() : req.getCategory()).name());
            boolean startChanged = !Objects.equals(s.getStartTime(), req.getStartTime());

            if (anonymousChanged || limitChanged || categoryChanged || startChanged) {
                throw BizException.badRequest("问卷已发布，只能修改标题、简介与截止时间；如需改动结构或设置，请复制为新问卷");
            }
        }

        validateTimeWindow(draft ? req.getStartTime() : s.getStartTime(), req.getEndTime());
        if (req.getEndTime() != null && s.getPublishedTime() != null
                && req.getEndTime().isBefore(LocalDateTime.now().minusDays(1))) {
            throw BizException.badRequest("截止时间不能早于当前时间");
        }

        s.setTitle(req.getTitle().trim());
        s.setDescription(trimToNull(req.getDescription()));
        if (draft) {
            s.setCategory(SurveyCategory.of(req.getCategory()).name());
            s.setAnonymous(bool(req.getAnonymous(), s.anonymousEnabled()));
            s.setLimitOnce(bool(req.getLimitOnce(), s.limitOnceEnabled()));
            s.setStartTime(req.getStartTime());
        }
        s.setEndTime(req.getEndTime());
        surveyMapper.updateById(s);

        operationLogService.write("问卷管理", "修改问卷", "SURVEY", s.getId(),
                "标题=" + s.getTitle(), httpReq);
        return toDetailVO(s);
    }

    /**
     * 删除问卷（逻辑删除）。
     *
     * @param confirm 已回收答卷数 > 0 时必须传 true，否则返回 409 让前端二次确认
     */
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id, boolean confirm, HttpServletRequest httpReq) {
        Survey s = requireOwned(loadSurvey(id), "survey:delete:self", "survey:delete:all");
        if (!confirm && s.getAnswerCount() != null && s.getAnswerCount() > 0) {
            throw BizException.conflict("该问卷已回收 " + s.getAnswerCount()
                    + " 份答卷，删除后数据仍保留但列表不可见；确认删除请传 confirm=true");
        }
        surveyMapper.deleteById(s.getId());
        operationLogService.write("问卷管理", "删除问卷", "SURVEY", s.getId(),
                "标题=" + s.getTitle() + "，已回收=" + s.getAnswerCount(), httpReq);
    }

    /**
     * 复制问卷（含题目与选项），生成新的草稿。
     * <p>
     * 用于「已发布问卷需要改结构」的唯一正确姿势。
     * 分享码、发布时间、结束时间一概不复制：副本是全新问卷，需要重新发布。
     */
    @Transactional(rollbackFor = Exception.class)
    public SurveyDetailVO copy(Long id, HttpServletRequest httpReq) {
        LoginUser u = UserContext.require();
        if (!u.hasPermission("survey:copy")) {
            throw BizException.forbidden("您没有复制问卷的权限");
        }
        Survey src = requireOwned(loadSurvey(id), "survey:copy", "survey:list:all");

        Survey s = new Survey();
        s.setTitle(cut(src.getTitle() + "（副本）", 120));
        s.setDescription(src.getDescription());
        s.setCategory(src.getCategory());
        s.setAnonymous(src.getAnonymous());
        s.setLimitOnce(src.getLimitOnce());
        s.setStartTime(null);
        s.setEndTime(null);
        s.setStatus(SurveyStatus.DRAFT.name());
        s.setQuestionCount(src.getQuestionCount());
        s.setAnswerCount(0);
        surveyMapper.insert(s);

        List<SurveyQuestion> qs = listQuestions(src.getId());
        Map<Long, List<SurveyOption>> optMap = listOptionsByQuestion(qs);
        for (SurveyQuestion q : qs) {
            SurveyQuestion nq = new SurveyQuestion();
            BeanUtils.copyProperties(q, nq, "id", "surveyId", "createBy", "createTime", "updateBy", "updateTime");
            nq.setSurveyId(s.getId());
            questionMapper.insert(nq);
            for (SurveyOption o : optMap.getOrDefault(q.getId(), List.of())) {
                SurveyOption no = new SurveyOption();
                no.setQuestionId(nq.getId());
                no.setSurveyId(s.getId());
                no.setContent(o.getContent());
                no.setSortNo(o.getSortNo());
                optionMapper.insert(no);
            }
        }

        operationLogService.write("问卷管理", "复制问卷", "SURVEY", s.getId(),
                "来源问卷=" + src.getId(), httpReq);
        return toDetailVO(s);
    }

    // =========================================================
    // 题目差量保存
    // =========================================================

    /**
     * 整卷保存题目（只允许草稿）。
     * <p>
     * 差量策略：有 id 更新、无 id 新增、库里多出来的删除。
     * 选项没有外部引用（答卷还没产生），一律「删旧建新」最省心。
     */
    @Transactional(rollbackFor = Exception.class)
    public SurveyDetailVO saveQuestions(Long id, List<QuestionSaveRequest> reqs, HttpServletRequest httpReq) {
        Survey s = requireOwned(loadSurvey(id), "survey:update:self", "survey:update:all");
        if (!SurveyStatus.DRAFT.name().equals(s.getStatus())) {
            throw BizException.badRequest("问卷已发布，题目结构已锁定；如需调整请复制为新问卷");
        }
        if (reqs == null) {
            reqs = List.of();
        }
        if (reqs.size() > MAX_QUESTION) {
            throw BizException.badRequest("单份问卷最多 " + MAX_QUESTION + " 道题");
        }

        List<SurveyQuestion> existing = listQuestions(s.getId());
        Map<Long, SurveyQuestion> existingMap = new HashMap<>();
        for (SurveyQuestion q : existing) {
            existingMap.put(q.getId(), q);
        }
        Set<Long> keep = new HashSet<>();

        int sort = 0;
        for (QuestionSaveRequest r : reqs) {
            sort++;
            // List 请求体的元素级 @Valid 不会自动级联，这里做一次显式兜底校验，
            // 避免 title 为 null 时在下面 trim() 抛 NPE 变成 500
            if (r == null) {
                throw BizException.badRequest("第 " + sort + " 道题为空");
            }
            if (r.getTitle() == null || r.getTitle().isBlank()) {
                throw BizException.badRequest("第 " + sort + " 道题的题干不能为空");
            }
            QuestionType type = QuestionType.parse(r.getQuestionType());
            List<OptionSaveRequest> opts = r.getOptions() == null ? List.of() : r.getOptions();
            validateQuestion(type, r, opts);

            SurveyQuestion q;
            if (r.getId() != null) {
                q = existingMap.get(r.getId());
                if (q == null) {
                    throw BizException.badRequest("题目 ID " + r.getId() + " 不属于该问卷");
                }
                keep.add(q.getId());
            } else {
                q = new SurveyQuestion();
                q.setSurveyId(s.getId());
            }
            q.setQuestionType(type.name());
            q.setTitle(r.getTitle().trim());
            q.setDescription(trimToNull(r.getDescription()));
            q.setIsRequired(bool(r.getIsRequired(), false));
            q.setMaxLength(type.isText() ? r.getMaxLength() : null);
            q.setRatingMax(type == QuestionType.RATING ? (r.getRatingMax() == null ? 5 : r.getRatingMax()) : null);
            q.setSortNo(sort);

            if (q.getId() == null) {
                questionMapper.insert(q);
            } else {
                questionMapper.updateById(q);
            }

            // 选项：先清空再按新顺序写入
            optionMapper.delete(Wrappers.<SurveyOption>lambdaQuery()
                    .eq(SurveyOption::getQuestionId, q.getId()));
            if (type.isNeedOptions()) {
                int os = 0;
                for (OptionSaveRequest o : opts) {
                    os++;
                    SurveyOption opt = new SurveyOption();
                    opt.setQuestionId(q.getId());
                    opt.setSurveyId(s.getId());
                    opt.setContent(o.getContent().trim());
                    opt.setSortNo(os);
                    optionMapper.insert(opt);
                }
            }
        }

        // 删除本次未提交的旧题目及其选项
        for (SurveyQuestion q : existing) {
            if (!keep.contains(q.getId())) {
                optionMapper.delete(Wrappers.<SurveyOption>lambdaQuery()
                        .eq(SurveyOption::getQuestionId, q.getId()));
                questionMapper.deleteById(q.getId());
            }
        }

        s.setQuestionCount(sort);
        surveyMapper.updateById(s);

        operationLogService.write("问卷管理", "保存题目", "SURVEY", s.getId(),
                "题目数=" + sort, httpReq);
        return toDetailVO(s);
    }

    // =========================================================
    // 发布 / 暂停 / 恢复 / 结束
    // =========================================================

    @Transactional(rollbackFor = Exception.class)
    public SurveyVO publish(Long id, HttpServletRequest httpReq) {
        Survey s = requireOwned(loadSurvey(id), "survey:publish:self", "survey:publish:all");
        if (!SurveyStatus.DRAFT.name().equals(s.getStatus())) {
            throw BizException.badRequest("只有草稿状态的问卷可以发布，当前状态：" + statusLabel(s.getStatus()));
        }
        long qc = questionMapper.selectCount(Wrappers.<SurveyQuestion>lambdaQuery()
                .eq(SurveyQuestion::getSurveyId, s.getId()));
        if (qc <= 0) {
            throw BizException.badRequest("问卷至少需要 1 道题才能发布");
        }
        validateTimeWindow(s.getStartTime(), s.getEndTime());

        s.setStatus(SurveyStatus.PUBLISHED.name());
        s.setQuestionCount((int) qc);
        s.setShareCode(generateShareCode());
        if (s.getPublishedTime() == null) {
            s.setPublishedTime(LocalDateTime.now());
        }
        surveyMapper.updateById(s);

        operationLogService.write("问卷管理", "发布问卷", "SURVEY", s.getId(),
                "分享码=" + s.getShareCode(), httpReq);
        return toVO(s, null);
    }

    @Transactional(rollbackFor = Exception.class)
    public SurveyVO pause(Long id, HttpServletRequest httpReq) {
        Survey s = requireOwned(loadSurvey(id), "survey:publish:self", "survey:publish:all");
        if (!SurveyStatus.PUBLISHED.name().equals(s.getStatus())) {
            throw BizException.badRequest("只有已发布的问卷可以暂停，当前状态：" + statusLabel(s.getStatus()));
        }
        s.setStatus(SurveyStatus.PAUSED.name());
        surveyMapper.updateById(s);
        operationLogService.write("问卷管理", "暂停回收", "SURVEY", s.getId(), null, httpReq);
        return toVO(s, null);
    }

    @Transactional(rollbackFor = Exception.class)
    public SurveyVO resume(Long id, HttpServletRequest httpReq) {
        Survey s = requireOwned(loadSurvey(id), "survey:publish:self", "survey:publish:all");
        if (!SurveyStatus.PAUSED.name().equals(s.getStatus())) {
            throw BizException.badRequest("只有已暂停的问卷可以恢复，当前状态：" + statusLabel(s.getStatus()));
        }
        if (s.getEndTime() != null && s.getEndTime().isBefore(LocalDateTime.now())) {
            throw BizException.badRequest("问卷已过截止时间，无法恢复回收；请先延长截止时间");
        }
        s.setStatus(SurveyStatus.PUBLISHED.name());
        surveyMapper.updateById(s);
        operationLogService.write("问卷管理", "恢复回收", "SURVEY", s.getId(), null, httpReq);
        return toVO(s, null);
    }

    /** 结束问卷：不可逆，PUBLISHED/PAUSED 均可结束 */
    @Transactional(rollbackFor = Exception.class)
    public SurveyVO close(Long id, HttpServletRequest httpReq) {
        Survey s = requireOwned(loadSurvey(id), "survey:publish:self", "survey:publish:all");
        if (!SurveyStatus.PUBLISHED.name().equals(s.getStatus())
                && !SurveyStatus.PAUSED.name().equals(s.getStatus())) {
            throw BizException.badRequest("只有已发布或已暂停的问卷可以结束，当前状态：" + statusLabel(s.getStatus()));
        }
        s.setStatus(SurveyStatus.CLOSED.name());
        s.setClosedTime(LocalDateTime.now());
        surveyMapper.updateById(s);
        operationLogService.write("问卷管理", "结束问卷", "SURVEY", s.getId(), null, httpReq);
        return toVO(s, null);
    }

    // =========================================================
    // 归属与权限
    // =========================================================

    /**
     * 读取问卷，不存在（含已删除）抛 404。
     */
    public Survey loadSurvey(Long id) {
        Survey s = surveyMapper.selectById(id);
        if (s == null) {
            throw BizException.notFound("问卷不存在或已被删除");
        }
        return s;
    }

    /**
     * 归属校验：本人创建，或具备 allPerm 指定的全量权限。
     *
     * @param selfPerm 数据归属为本人时需要的权限码
     * @param allPerm  具备该权限时跳过归属校验
     */
    public Survey requireOwned(Survey s, String selfPerm, String allPerm) {
        LoginUser u = UserContext.require();
        if (allPerm != null && u.hasPermission(allPerm)) {
            return s;
        }
        if (selfPerm != null && u.hasPermission(selfPerm)
                && Objects.equals(s.getCreateBy(), u.getUserId())) {
            return s;
        }
        if (!Objects.equals(s.getCreateBy(), u.getUserId())) {
            throw BizException.forbidden("无权操作他人的问卷");
        }
        throw BizException.forbidden("您没有执行该操作的权限");
    }

    // =========================================================
    // 内部工具
    // =========================================================

    private void validateQuestion(QuestionType type, QuestionSaveRequest r, List<OptionSaveRequest> opts) {
        // 请求体是 List，元素级的 @Size/@Min/@Max 不会被 Spring 级联校验，
        // 因此长度与取值范围的校验必须在这里手动做，否则前端传超长题干会直接入库报错。
        String t = r.getTitle() == null ? "" : r.getTitle().trim();
        if (t.length() > 255) {
            throw BizException.badRequest("题干不能超过 255 字");
        }
        if (r.getDescription() != null && r.getDescription().length() > 500) {
            throw BizException.badRequest("题目说明不能超过 500 字");
        }
        if (type.isText() && r.getMaxLength() != null
                && (r.getMaxLength() < 1 || r.getMaxLength() > 2000)) {
            throw BizException.badRequest("文本题最大字数应在 1~2000 之间");
        }
        if (type == QuestionType.RATING && r.getRatingMax() != null
                && (r.getRatingMax() < 2 || r.getRatingMax() > 10)) {
            throw BizException.badRequest("评分上限应在 2~10 之间");
        }

        if (type.isNeedOptions()) {
            if (opts.size() < 2) {
                throw BizException.badRequest("「" + r.getTitle() + "」至少需要 2 个选项");
            }
            Set<String> seen = new HashSet<>();
            for (OptionSaveRequest o : opts) {
                if (o == null) {
                    throw BizException.badRequest("「" + r.getTitle() + "」存在空选项");
                }
                String c = o.getContent() == null ? "" : o.getContent().trim();
                if (c.isEmpty()) {
                    throw BizException.badRequest("「" + r.getTitle() + "」存在空选项");
                }
                if (c.length() > 255) {
                    throw BizException.badRequest("「" + r.getTitle() + "」的选项内容不能超过 255 字");
                }
                if (!seen.add(c)) {
                    throw BizException.badRequest("「" + r.getTitle() + "」存在重复选项：" + c);
                }
            }
        } else if (!opts.isEmpty()) {
            throw BizException.badRequest("「" + r.getTitle() + "」不是选择题，不能带选项");
        }
    }

    private void validateTimeWindow(LocalDateTime start, LocalDateTime end) {
        if (start != null && end != null && !end.isAfter(start)) {
            throw BizException.badRequest("截止时间必须晚于开始时间");
        }
    }

    /** 生成全局唯一分享码，冲突重试 5 次后放弃 */
    private String generateShareCode() {
        for (int i = 0; i < 5; i++) {
            StringBuilder sb = new StringBuilder(SHARE_CODE_LEN);
            for (int j = 0; j < SHARE_CODE_LEN; j++) {
                sb.append(SHARE_ALPHABET[RANDOM.nextInt(SHARE_ALPHABET.length)]);
            }
            String code = sb.toString();
            Long exists = surveyMapper.selectCount(Wrappers.<Survey>lambdaQuery()
                    .eq(Survey::getShareCode, code));
            if (exists == null || exists == 0) {
                return code;
            }
        }
        throw BizException.conflict("分享码生成失败，请重试");
    }

    public List<SurveyQuestion> listQuestions(Long surveyId) {
        return questionMapper.selectList(Wrappers.<SurveyQuestion>lambdaQuery()
                .eq(SurveyQuestion::getSurveyId, surveyId)
                .orderByAsc(SurveyQuestion::getSortNo)
                .orderByAsc(SurveyQuestion::getId));
    }

    public Map<Long, List<SurveyOption>> listOptionsByQuestion(List<SurveyQuestion> qs) {
        if (qs.isEmpty()) {
            return Map.of();
        }
        List<Long> ids = qs.stream().map(SurveyQuestion::getId).toList();
        List<SurveyOption> opts = optionMapper.selectList(Wrappers.<SurveyOption>lambdaQuery()
                .in(SurveyOption::getQuestionId, ids)
                .orderByAsc(SurveyOption::getQuestionId)
                .orderByAsc(SurveyOption::getSortNo)
                .orderByAsc(SurveyOption::getId));
        Map<Long, List<SurveyOption>> map = new LinkedHashMap<>();
        for (SurveyOption o : opts) {
            map.computeIfAbsent(o.getQuestionId(), k -> new ArrayList<>()).add(o);
        }
        return map;
    }

    public SurveyDetailVO toDetailVO(Survey s) {
        SurveyDetailVO vo = new SurveyDetailVO();
        BeanUtils.copyProperties(toVO(s, null), vo);
        List<SurveyQuestion> qs = listQuestions(s.getId());
        Map<Long, List<SurveyOption>> optMap = listOptionsByQuestion(qs);
        List<QuestionVO> list = new ArrayList<>();
        for (SurveyQuestion q : qs) {
            QuestionVO qv = new QuestionVO();
            BeanUtils.copyProperties(q, qv);
            List<OptionVO> ovs = new ArrayList<>();
            for (SurveyOption o : optMap.getOrDefault(q.getId(), List.of())) {
                OptionVO ov = new OptionVO();
                BeanUtils.copyProperties(o, ov);
                ovs.add(ov);
            }
            qv.setOptions(ovs);
            list.add(qv);
        }
        vo.setQuestions(list);
        return vo;
    }

    public List<SurveyVO> toVOList(List<Survey> records) {
        if (records.isEmpty()) {
            return List.of();
        }
        // 批量取创建人姓名，避免 N+1
        Set<Long> uidSet = new HashSet<>();
        for (Survey s : records) {
            if (s.getCreateBy() != null) {
                uidSet.add(s.getCreateBy());
            }
        }
        Map<Long, String> nameMap = new HashMap<>();
        if (!uidSet.isEmpty()) {
            for (SysUser u : userMapper.selectBatchIds(uidSet)) {
                nameMap.put(u.getId(), u.getRealName());
            }
        }
        List<SurveyVO> list = new ArrayList<>(records.size());
        for (Survey s : records) {
            list.add(toVO(s, nameMap));
        }
        return list;
    }

    public SurveyVO toVO(Survey s, Map<Long, String> nameMap) {
        SurveyVO vo = new SurveyVO();
        BeanUtils.copyProperties(s, vo);
        vo.setCategoryLabel(SurveyCategory.of(s.getCategory()).getLabel());
        LocalDateTime now = LocalDateTime.now();
        vo.setFillable(SurveyStatus.PUBLISHED.name().equals(s.getStatus()) && s.inTimeWindow(now));
        if (nameMap != null && s.getCreateBy() != null) {
            vo.setCreateByName(nameMap.get(s.getCreateBy()));
        } else if (s.getCreateBy() != null) {
            SysUser u = userMapper.selectById(s.getCreateBy());
            vo.setCreateByName(u == null ? null : u.getRealName());
        }
        return vo;
    }

    private String statusLabel(String status) {
        SurveyStatus st = SurveyStatus.of(status);
        if (st == null) {
            return status;
        }
        return switch (st) {
            case DRAFT -> "草稿";
            case PUBLISHED -> "已发布";
            case PAUSED -> "已暂停";
            case CLOSED -> "已结束";
        };
    }

    private static Integer bool(Integer v, boolean dft) {
        if (v == null) {
            return dft ? 1 : 0;
        }
        return v != 0 ? 1 : 0;
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static String cut(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    /** 供其他模块（答卷/统计）取题型显示名 */
    public static List<String> allQuestionTypes() {
        List<String> list = new ArrayList<>();
        for (QuestionType t : QuestionType.values()) {
            list.add(t.name());
        }
        return Collections.unmodifiableList(list);
    }
}
