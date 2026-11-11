package com.yjh.backend1.module.template.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yjh.backend1.common.BizException;
import com.yjh.backend1.common.PageResult;
import com.yjh.backend1.module.log.service.OperationLogService;
import com.yjh.backend1.module.survey.dto.OptionVO;
import com.yjh.backend1.module.survey.dto.QuestionSaveRequest;
import com.yjh.backend1.module.survey.dto.QuestionVO;
import com.yjh.backend1.module.survey.dto.SurveyDetailVO;
import com.yjh.backend1.module.survey.dto.SurveySaveRequest;
import com.yjh.backend1.module.survey.entity.Survey;
import com.yjh.backend1.module.survey.entity.SurveyOption;
import com.yjh.backend1.module.survey.entity.SurveyQuestion;
import com.yjh.backend1.module.survey.enums.SurveyCategory;
import com.yjh.backend1.module.survey.service.SurveyService;
import com.yjh.backend1.module.template.dto.AuditTemplateRequest;
import com.yjh.backend1.module.template.dto.ShareTemplateRequest;
import com.yjh.backend1.module.template.dto.TemplateQuery;
import com.yjh.backend1.module.template.dto.TemplateQuestionVO;
import com.yjh.backend1.module.template.dto.TemplateStructure;
import com.yjh.backend1.module.template.dto.TemplateVO;
import com.yjh.backend1.module.template.entity.SurveyTemplate;
import com.yjh.backend1.module.template.entity.TemplateRating;
import com.yjh.backend1.module.template.enums.TemplateStatus;
import com.yjh.backend1.module.template.mapper.SurveyTemplateMapper;
import com.yjh.backend1.module.template.mapper.TemplateRatingMapper;
import com.yjh.backend1.module.user.entity.SysUser;
import com.yjh.backend1.module.user.mapper.SysUserMapper;
import com.yjh.backend1.security.LoginUser;
import com.yjh.backend1.security.UserContext;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 模板中心：共享、审核、浏览、评分、使用。
 * <p>
 * 四个贯穿全类的约束：
 * <ul>
 *   <li><b>模板是快照，不是引用</b>：共享那一刻把题目结构序列化进 {@code structure_json}。
 *       来源问卷之后怎么改、删，都不影响模板；「使用模板」也只读 JSON，不回查来源问卷。</li>
 *   <li><b>只有 APPROVED 对外可见</b>：模板中心的查询恒定带 {@code status=APPROVED}，
 *       不从请求参数取值 —— 否则前端传个 {@code status=PENDING} 就能绕过审核看到未过审内容。</li>
 *   <li><b>待审内容的可见性按身份分</b>：共享者能看自己的全部状态（包括被驳回的原因），
 *       审核员能看 PENDING，其他人只能看 APPROVED。</li>
 *   <li><b>评分一人一票、可改不可删</b>：靠 {@code uk_template_user} 唯一索引兜底，
 *       计数与总和与评分行在同一事务里增减，不允许出现「有评分行但计数没变」。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TemplateService {

    private final SurveyTemplateMapper templateMapper;
    private final TemplateRatingMapper ratingMapper;
    private final SurveyService surveyService;
    private final SysUserMapper userMapper;
    private final OperationLogService operationLogService;
    private final ObjectMapper objectMapper;

    private static final int MAX_PAGE_SIZE = 100;
    private static final int MIN_SCORE = 1;
    private static final int MAX_SCORE = 5;

    // =========================================================
    // 共享 / 下架
    // =========================================================

    /**
     * 把自己的问卷共享为模板（FR-TPL-01/02）。
     * <p>
     * 提交后状态为 {@code PENDING}，必须 ADMIN 审核通过才在模板中心可见。
     * 来源问卷必须是自己的、且至少有一道题 —— 空模板共享出去对使用者毫无价值，
     * 还会让审核员白点一次。
     */
    @Transactional(rollbackFor = Exception.class)
    public TemplateVO share(ShareTemplateRequest req, HttpServletRequest httpReq) {
        LoginUser u = UserContext.require();
        if (!u.hasPermission("template:share")) {
            throw BizException.forbidden("您没有共享模板的权限");
        }

        Survey src = surveyService.loadSurvey(req.getSourceSurveyId());
        // 复用问卷模块的归属校验：非本人且无 survey:list:all 权限时抛 403
        surveyService.requireOwned(src, "survey:update:self", "survey:list:all");

        List<SurveyQuestion> questions = surveyService.listQuestions(src.getId());
        if (questions.isEmpty()) {
            throw BizException.badRequest("该问卷还没有题目，无法共享为模板");
        }

        SurveyTemplate t = new SurveyTemplate();
        t.setTitle(req.getTitle().trim());
        t.setDescription(trimToNull(req.getDescription()));
        t.setCategory(req.getCategory() == null || req.getCategory().isBlank()
                ? SurveyCategory.of(src.getCategory()).name()
                : parseCategory(req.getCategory()).name());
        t.setSourceSurveyId(src.getId());
        t.setQuestionCount(questions.size());
        t.setAnonymousDefault(intOr(req.getAnonymousDefault(), src.getAnonymous()));
        t.setLimitOnceDefault(intOr(req.getLimitOnceDefault(), src.getLimitOnce()));
        t.setStructureJson(writeStructure(snapshot(questions)));
        t.setStatus(TemplateStatus.PENDING.name());
        t.setSubmitTime(LocalDateTime.now());
        t.setRatingCount(0);
        t.setRatingSum(0);
        t.setUseCount(0);
        templateMapper.insert(t);

        operationLogService.write("模板管理", "共享为模板", "TEMPLATE", t.getId(),
                "模板《" + t.getTitle() + "》来源问卷=" + src.getId() + "，题数=" + questions.size(), httpReq);
        return toVOWithNames(t, null, null);
    }

    /**
     * 重新提交已被驳回的模板（FR-TPL-02 的「可修改后重新提交」）。
     * <p>
     * 只允许 REJECTED → PENDING。已通过的模板要改内容，正确做法是下架后重新共享，
     * 而不是原地改 —— 已被别人使用的模板内容发生变化，会让用它的问卷与描述不一致。
     */
    @Transactional(rollbackFor = Exception.class)
    public TemplateVO resubmit(Long id, HttpServletRequest httpReq) {
        SurveyTemplate t = requireOwned(load(id), "template:share");
        if (!TemplateStatus.REJECTED.name().equals(t.getStatus())) {
            throw BizException.badRequest("只有被驳回的模板可以重新提交，当前状态：" + statusLabel(t.getStatus()));
        }
        t.setStatus(TemplateStatus.PENDING.name());
        t.setSubmitTime(LocalDateTime.now());
        // 驳回原因、审核人、审核时间必须显式清空。
        // 不能用 updateById(t)：MyBatis-Plus 默认跳过 null 字段，那样旧的驳回意见会留着，
        // 共享者重新提交后仍能看到上一次的驳回原因，误以为还没处理。
        templateMapper.update(null, Wrappers.<SurveyTemplate>lambdaUpdate()
                .eq(SurveyTemplate::getId, t.getId())
                .set(SurveyTemplate::getStatus, t.getStatus())
                .set(SurveyTemplate::getSubmitTime, t.getSubmitTime())
                .set(SurveyTemplate::getAuditRemark, null)
                .set(SurveyTemplate::getAuditBy, null)
                .set(SurveyTemplate::getAuditTime, null));
        operationLogService.write("模板管理", "重新提交模板", "TEMPLATE", t.getId(),
                "模板《" + t.getTitle() + "》", httpReq);
        // 必须重新查一次：上面的 update 走的是 SQL，内存里的 t 还带着旧的驳回原因，
        // 直接拿它 toVO 会把已清空的字段又回给前端
        return toVOWithNames(load(t.getId()), null, null);
    }

    /**
     * 下架自己的模板（FR-TPL-07）。
     * <p>
     * APPROVED 与 PENDING 都可以下架。已下架的模板不再出现在模板中心，
     * 但已用它创建过的问卷不受影响（那是独立副本）。
     */
    @Transactional(rollbackFor = Exception.class)
    public TemplateVO offline(Long id, HttpServletRequest httpReq) {
        SurveyTemplate t = requireOwned(load(id), "template:offline");
        TemplateStatus st = TemplateStatus.of(t.getStatus());
        if (st == TemplateStatus.OFFLINE) {
            throw BizException.conflict("该模板已下架");
        }
        if (st == TemplateStatus.REJECTED) {
            throw BizException.badRequest("被驳回的模板无需下架，可直接删除或修改后重新提交");
        }
        t.setStatus(TemplateStatus.OFFLINE.name());
        templateMapper.updateById(t);
        operationLogService.write("模板管理", "下架模板", "TEMPLATE", t.getId(),
                "模板《" + t.getTitle() + "》原状态=" + statusLabel(st == null ? null : st.name()), httpReq);
        return toVOWithNames(t, null, null);
    }

    /**
     * 删除模板（逻辑删除）。
     * <p>
     * 只允许删自己共享的，且只有 REJECTED / OFFLINE 可删 ——
     * 待审模板应先驳回再删，已通过模板应先下架，这样日志里留下完整轨迹，
     * 也避免共享者在审核员处理到一半时把记录抽走。
     */
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id, HttpServletRequest httpReq) {
        SurveyTemplate t = requireOwned(load(id), "template:share");
        TemplateStatus st = TemplateStatus.of(t.getStatus());
        if (st != TemplateStatus.REJECTED && st != TemplateStatus.OFFLINE) {
            throw BizException.badRequest("只能删除已驳回或已下架的模板；"
                    + "待审核模板请等待审核结果，已通过的模板请先下架");
        }
        templateMapper.deleteById(t.getId());
        operationLogService.write("模板管理", "删除模板", "TEMPLATE", t.getId(),
                "模板《" + t.getTitle() + "》", httpReq);
    }

    // =========================================================
    // 审核
    // =========================================================

    /**
     * 审核模板（FR-TPL-03）。
     * <p>
     * 驳回必须填原因：共享者看到「已驳回」却不知为何，只能反复重提交，
     * 审核员每次都要重看一遍，两边都被坑。
     */
    @Transactional(rollbackFor = Exception.class)
    public TemplateVO audit(Long id, AuditTemplateRequest req, HttpServletRequest httpReq) {
        LoginUser u = UserContext.require();
        if (!u.hasPermission("template:audit")) {
            throw BizException.forbidden("您没有审核模板的权限");
        }
        TemplateStatus target = TemplateStatus.parse(req.getAuditStatus());
        if (target != TemplateStatus.APPROVED && target != TemplateStatus.REJECTED) {
            throw BizException.badRequest("审核结果只能是 APPROVED 或 REJECTED");
        }
        String remark = trimToNull(req.getAuditRemark());
        if (target == TemplateStatus.REJECTED && remark == null) {
            throw BizException.badRequest("驳回时必须填写审核意见");
        }

        SurveyTemplate t = load(id);
        if (!TemplateStatus.PENDING.name().equals(t.getStatus())) {
            throw BizException.badRequest("只有待审核的模板可以审核，当前状态：" + statusLabel(t.getStatus()));
        }
        t.setStatus(target.name());
        t.setAuditRemark(remark);
        t.setAuditBy(u.getUserId());
        t.setAuditTime(LocalDateTime.now());
        templateMapper.updateById(t);

        operationLogService.write("模板管理", target == TemplateStatus.APPROVED ? "审核通过" : "审核驳回",
                "TEMPLATE", t.getId(),
                "模板《" + t.getTitle() + "》" + (remark == null ? "" : "，意见=" + remark), httpReq);
        return toVOWithNames(t, null, null);
    }

    // =========================================================
    // 浏览 / 详情
    // =========================================================

    /**
     * 模板分页列表。
     * <p>
     * 三个视角共用这一个方法，但可见范围完全不同：
     * <ul>
     *   <li>{@code browse} 模板中心：强制 {@code status=APPROVED}，任何请求参数都改不了</li>
     *   <li>{@code mine} 我共享的：仅 {@code create_by=自己}，全部状态可见</li>
     *   <li>{@code audit} 待审核：需要 {@code template:audit}，默认只看 PENDING</li>
     * </ul>
     */
    public PageResult<TemplateVO> page(TemplateQuery q) {
        LoginUser u = UserContext.require();
        if (!u.hasPermission("template:browse") && !u.hasPermission("template:share")
                && !u.hasPermission("template:audit")) {
            throw BizException.forbidden("您没有浏览模板的权限");
        }
        int page = q.getPage() == null || q.getPage() < 1 ? 1 : q.getPage();
        int size = q.getSize() == null || q.getSize() < 1 ? 20 : Math.min(q.getSize(), MAX_PAGE_SIZE);
        String scope = q.getScope() == null ? "browse" : q.getScope().trim().toLowerCase();

        LambdaQueryWrapper<SurveyTemplate> w = Wrappers.lambdaQuery();
        switch (scope) {
            case "mine" -> {
                if (!u.hasPermission("template:share") && !u.hasPermission("template:offline")) {
                    throw BizException.forbidden("您没有共享模板的权限");
                }
                w.eq(SurveyTemplate::getCreateBy, u.getUserId());
                if (q.getStatus() != null && !q.getStatus().isBlank()) {
                    w.eq(SurveyTemplate::getStatus, TemplateStatus.parse(q.getStatus()).name());
                }
            }
            case "audit" -> {
                if (!u.hasPermission("template:audit")) {
                    throw BizException.forbidden("您没有审核模板的权限");
                }
                if (q.getStatus() != null && !q.getStatus().isBlank()) {
                    w.eq(SurveyTemplate::getStatus, TemplateStatus.parse(q.getStatus()).name());
                } else {
                    w.eq(SurveyTemplate::getStatus, TemplateStatus.PENDING.name());
                }
            }
            case "browse" -> w.eq(SurveyTemplate::getStatus, TemplateStatus.APPROVED.name());
            default -> throw BizException.badRequest("未知的查询视角：" + scope);
        }

        if (q.getKeyword() != null && !q.getKeyword().isBlank()) {
            w.like(SurveyTemplate::getTitle, q.getKeyword().trim());
        }
        if (q.getCategory() != null && !q.getCategory().isBlank()) {
            w.eq(SurveyTemplate::getCategory, parseCategory(q.getCategory()).name());
        }

        String order = q.getOrder() == null ? "rating" : q.getOrder().trim().toLowerCase();
        switch (order) {
            case "latest" -> w.orderByDesc(SurveyTemplate::getId);
            case "used" -> w.orderByDesc(SurveyTemplate::getUseCount).orderByDesc(SurveyTemplate::getId);
            // 默认按评分。不要加 isNotNull：那会把「还没人评分」的模板直接从列表里剔除，
            // 新共享的模板永远等不到第一个评分。MySQL 认为 NULL 最小，DESC 时自然排在最后。
            case "rating" -> w.orderByDesc(SurveyTemplate::getRatingAvg)
                    .orderByDesc(SurveyTemplate::getRatingCount)
                    .orderByDesc(SurveyTemplate::getId);
            default -> throw BizException.badRequest("未知的排序方式：" + order);
        }

        Page<SurveyTemplate> p = templateMapper.selectPage(new Page<>(page, size), w);
        return PageResult.of(toVOList(p.getRecords()), p.getTotal(), p.getCurrent(), p.getSize());
    }

    /**
     * 模板详情（FR-TPL-04）。
     * <p>
     * 可见性规则：APPROVED 所有人可见；其余状态只有共享者与审核员可见。
     * 直接返回 404 而不是 403 —— 未过审的模板对普通用户来说「不存在」，
     * 返回 403 等于告诉对方「这里有个模板，只是不给你看」。
     */
    public TemplateVO detail(Long id) {
        LoginUser u = UserContext.require();
        SurveyTemplate t = load(id);
        if (!TemplateStatus.APPROVED.name().equals(t.getStatus())) {
            boolean owner = Objects.equals(t.getCreateBy(), u.getUserId());
            boolean auditor = u.hasPermission("template:audit");
            if (!owner && !auditor) {
                throw BizException.notFound("模板不存在或未通过审核");
            }
        }
        TemplateStructure structure = readStructure(t.getStructureJson());
        Integer myScore = findMyScore(t.getId(), u.getUserId());
        return toVOWithNames(t, structure.getQuestions(), myScore);
    }

    // =========================================================
    // 使用模板 / 评分
    // =========================================================

    /**
     * 使用模板创建自己的问卷草稿（FR-TPL-06）。
     * <p>
     * 生成的是**全新问卷**：题目、选项都重新写入 survey 系列表，
     * 与模板、与来源问卷都没有关联。这样使用者可以自由改题，
     * 也不会出现「改了自己的问卷，别人的模板跟着变」。
     * <p>
     * 匿名与限填一次取模板上的默认值，使用者可在草稿阶段再改。
     */
    @Transactional(rollbackFor = Exception.class)
    public SurveyDetailVO use(Long id, HttpServletRequest httpReq) {
        LoginUser u = UserContext.require();
        if (!u.hasPermission("template:use")) {
            throw BizException.forbidden("您没有使用模板的权限");
        }
        if (!u.hasPermission("survey:create")) {
            throw BizException.forbidden("您没有创建问卷的权限");
        }

        SurveyTemplate t = load(id);
        if (!TemplateStatus.APPROVED.name().equals(t.getStatus())) {
            throw BizException.badRequest("该模板未通过审核，无法使用");
        }
        TemplateStructure structure = readStructure(t.getStructureJson());
        if (structure.getQuestions() == null || structure.getQuestions().isEmpty()) {
            throw BizException.badRequest("模板内容为空，无法使用");
        }

        SurveySaveRequest sr = new SurveySaveRequest();
        sr.setTitle(cut(t.getTitle(), 120));
        sr.setDescription(t.getDescription());
        sr.setCategory(t.getCategory());
        sr.setAnonymous(t.getAnonymousDefault());
        sr.setLimitOnce(t.getLimitOnceDefault());
        SurveyDetailVO created = surveyService.create(sr, httpReq);

        List<QuestionSaveRequest> qs = new ArrayList<>(structure.getQuestions().size());
        for (TemplateQuestionVO tq : structure.getQuestions()) {
            QuestionSaveRequest q = new QuestionSaveRequest();
            q.setQuestionType(tq.getQuestionType());
            q.setTitle(tq.getTitle());
            q.setDescription(tq.getDescription());
            q.setIsRequired(tq.getIsRequired());
            q.setMaxLength(tq.getMaxLength());
            q.setRatingMax(tq.getRatingMax());
            if (tq.getOptions() != null) {
                List<com.yjh.backend1.module.survey.dto.OptionSaveRequest> opts = new ArrayList<>();
                for (String text : tq.getOptions()) {
                    com.yjh.backend1.module.survey.dto.OptionSaveRequest o =
                            new com.yjh.backend1.module.survey.dto.OptionSaveRequest();
                    o.setContent(text);
                    opts.add(o);
                }
                q.setOptions(opts);
            }
            qs.add(q);
        }
        SurveyDetailVO detail = surveyService.saveQuestions(created.getId(), qs, httpReq);

        // 使用次数用 SQL 自增而不是「读出来 +1 再写回」：并发使用时读改写会丢计数
        templateMapper.update(null, Wrappers.<SurveyTemplate>lambdaUpdate()
                .eq(SurveyTemplate::getId, t.getId())
                .setSql("use_count = use_count + 1"));

        operationLogService.write("模板管理", "使用模板建卷", "TEMPLATE", t.getId(),
                "模板《" + t.getTitle() + "》→ 新问卷=" + created.getId(), httpReq);
        return detail;
    }

    /**
     * 给模板评分（FR-TPL-05）。
     * <p>
     * 一人一模板一票，可改不可重复：先查已有评分，有则改分并调整总和，无则插入并 +1 人数。
     * 计数与总和必须与评分行在同一事务里改，否则会出现「评分行写进去了但列表还没变」。
     * <p>
     * 并发下的重复插入由 {@code uk_template_user} 唯一索引挡住，转成 409 提示重试。
     */
    @Transactional(rollbackFor = Exception.class)
    public TemplateVO rate(Long id, int score, HttpServletRequest httpReq) {
        LoginUser u = UserContext.require();
        if (!u.hasPermission("template:rate")) {
            throw BizException.forbidden("您没有评分模板的权限");
        }
        if (score < MIN_SCORE || score > MAX_SCORE) {
            throw BizException.badRequest("评分必须在 " + MIN_SCORE + "~" + MAX_SCORE + " 之间");
        }
        SurveyTemplate t = load(id);
        if (!TemplateStatus.APPROVED.name().equals(t.getStatus())) {
            throw BizException.badRequest("只能给已通过审核的模板评分");
        }

        TemplateRating exist = ratingMapper.selectOne(Wrappers.<TemplateRating>lambdaQuery()
                .eq(TemplateRating::getTemplateId, t.getId())
                .eq(TemplateRating::getUserId, u.getUserId()));
        int delta;
        if (exist == null) {
            TemplateRating r = new TemplateRating();
            r.setTemplateId(t.getId());
            r.setUserId(u.getUserId());
            r.setScore(score);
            try {
                ratingMapper.insert(r);
            } catch (org.springframework.dao.DuplicateKeyException e) {
                // 同一用户并发点两次：以先写入的为准，让调用方重试即可看到正确结果
                throw BizException.conflict("您已经评过分了，请刷新后重试");
            }
            delta = score;
            templateMapper.update(null, Wrappers.<SurveyTemplate>lambdaUpdate()
                    .eq(SurveyTemplate::getId, t.getId())
                    .setSql("rating_count = rating_count + 1, rating_sum = rating_sum + " + delta));
        } else {
            if (Objects.equals(exist.getScore(), score)) {
                // 分数没变，不写库也不改计数，直接返回最新 VO（避免无意义的 update 与日志噪音）
                return toVOWithNames(load(t.getId()), null, score);
            }
            delta = score - exist.getScore();
            exist.setScore(score);
            ratingMapper.updateById(exist);
            templateMapper.update(null, Wrappers.<SurveyTemplate>lambdaUpdate()
                    .eq(SurveyTemplate::getId, t.getId())
                    .setSql("rating_sum = rating_sum + (" + delta + ")"));
        }

        SurveyTemplate fresh = load(t.getId());
        operationLogService.write("模板管理", exist == null ? "评分模板" : "修改评分",
                "TEMPLATE", t.getId(), "模板《" + fresh.getTitle() + "》评分=" + score, httpReq);
        return toVOWithNames(fresh, null, score);
    }

    // =========================================================
    // 内部工具
    // =========================================================

    private SurveyTemplate load(Long id) {
        SurveyTemplate t = templateMapper.selectById(id);
        if (t == null) {
            throw BizException.notFound("模板不存在或已被删除");
        }
        return t;
    }

    /** 模板归属校验：只要本人，模板没有 *:all 这种全量权限 */
    private SurveyTemplate requireOwned(SurveyTemplate t, String perm) {
        LoginUser u = UserContext.require();
        if (!u.hasPermission(perm)) {
            throw BizException.forbidden("您没有执行该操作的权限");
        }
        if (!Objects.equals(t.getCreateBy(), u.getUserId())) {
            throw BizException.forbidden("无权操作他人的模板");
        }
        return t;
    }

    private Integer findMyScore(Long templateId, Long userId) {
        if (templateId == null || userId == null) {
            return null;
        }
        TemplateRating r = ratingMapper.selectOne(Wrappers.<TemplateRating>lambdaQuery()
                .eq(TemplateRating::getTemplateId, templateId)
                .eq(TemplateRating::getUserId, userId));
        return r == null ? null : r.getScore();
    }

    /** 把问卷题目 + 选项拍平成模板结构快照（选项存文本，不存 ID） */
    private List<TemplateQuestionVO> snapshot(List<SurveyQuestion> questions) {
        Map<Long, List<SurveyOption>> optMap = surveyService.listOptionsByQuestion(questions);
        List<TemplateQuestionVO> list = new ArrayList<>(questions.size());
        int sort = 0;
        for (SurveyQuestion q : questions) {
            sort++;
            TemplateQuestionVO tq = new TemplateQuestionVO();
            tq.setQuestionType(q.getQuestionType());
            tq.setTitle(q.getTitle());
            tq.setDescription(q.getDescription());
            tq.setIsRequired(q.getIsRequired());
            tq.setMaxLength(q.getMaxLength());
            tq.setRatingMax(q.getRatingMax());
            tq.setSortNo(sort);
            List<String> texts = new ArrayList<>();
            for (SurveyOption o : optMap.getOrDefault(q.getId(), List.of())) {
                texts.add(o.getContent());
            }
            tq.setOptions(texts);
            list.add(tq);
        }
        return list;
    }

    private String writeStructure(List<TemplateQuestionVO> questions) {
        TemplateStructure s = new TemplateStructure();
        s.setQuestions(questions);
        try {
            return objectMapper.writeValueAsString(s);
        } catch (JsonProcessingException e) {
            // 序列化失败是本机 bug，不是用户输入问题，兜底 500 而不是静默存 "{}"
            throw new IllegalStateException("模板结构序列化失败", e);
        }
    }

    private TemplateStructure readStructure(String json) {
        if (json == null || json.isBlank()) {
            TemplateStructure empty = new TemplateStructure();
            empty.setQuestions(List.of());
            return empty;
        }
        try {
            TemplateStructure s = objectMapper.readValue(json, TemplateStructure.class);
            if (s.getQuestions() == null) {
                s.setQuestions(List.of());
            }
            return s;
        } catch (JsonProcessingException e) {
            // 存量数据格式损坏时不让整个详情页 500：返回空结构，前端提示「模板内容异常」
            log.warn("模板结构解析失败，按空结构处理：{}", e.getMessage());
            TemplateStructure empty = new TemplateStructure();
            empty.setQuestions(List.of());
            return empty;
        }
    }

    private List<TemplateVO> toVOList(List<SurveyTemplate> records) {
        if (records.isEmpty()) {
            return List.of();
        }
        Set<Long> uids = new HashSet<>();
        for (SurveyTemplate t : records) {
            if (t.getCreateBy() != null) {
                uids.add(t.getCreateBy());
            }
            if (t.getAuditBy() != null) {
                uids.add(t.getAuditBy());
            }
        }
        Map<Long, String> names = new HashMap<>();
        if (!uids.isEmpty()) {
            for (SysUser user : userMapper.selectBatchIds(uids)) {
                names.put(user.getId(), user.getRealName());
            }
        }
        // 列表页一次性把我对这些模板的评分查出来，避免每行一次查询
        Map<Long, Integer> myScores = new HashMap<>();
        LoginUser u = UserContext.get();
        if (u != null) {
            List<Long> ids = records.stream().map(SurveyTemplate::getId).toList();
            for (TemplateRating r : ratingMapper.selectList(Wrappers.<TemplateRating>lambdaQuery()
                    .eq(TemplateRating::getUserId, u.getUserId())
                    .in(TemplateRating::getTemplateId, ids))) {
                myScores.put(r.getTemplateId(), r.getScore());
            }
        }
        List<TemplateVO> list = new ArrayList<>(records.size());
        for (SurveyTemplate t : records) {
            TemplateVO vo = toVO(t, null, myScores.get(t.getId()));
            if (t.getCreateBy() != null) {
                vo.setCreateByName(names.get(t.getCreateBy()));
            }
            if (t.getAuditBy() != null) {
                vo.setAuditByName(names.get(t.getAuditBy()));
            }
            list.add(vo);
        }
        return list;
    }

    /**
     * 单个模板的 VO，补齐创建人与审核人姓名。
     * <p>
     * 审批、重新提交、下架这些写操作之后都要把最新状态回给前端，
     * 只靠 {@link #toVO} 会漏掉两个姓名字段，前端刚审核完就看到「审核人：--」，
     * 看起来像是没写进去。
     */
    private TemplateVO toVOWithNames(SurveyTemplate t, List<TemplateQuestionVO> structure, Integer myScore) {
        TemplateVO vo = toVO(t, structure, myScore);
        Set<Long> uids = new HashSet<>();
        if (t.getCreateBy() != null) {
            uids.add(t.getCreateBy());
        }
        if (t.getAuditBy() != null) {
            uids.add(t.getAuditBy());
        }
        if (!uids.isEmpty()) {
            Map<Long, String> names = new HashMap<>();
            for (SysUser user : userMapper.selectBatchIds(uids)) {
                names.put(user.getId(), user.getRealName());
            }
            if (t.getCreateBy() != null) {
                vo.setCreateByName(names.get(t.getCreateBy()));
            }
            if (t.getAuditBy() != null) {
                vo.setAuditByName(names.get(t.getAuditBy()));
            }
        }
        return vo;
    }

    private TemplateVO toVO(SurveyTemplate t, List<TemplateQuestionVO> structure, Integer myScore) {
        TemplateVO vo = new TemplateVO();
        vo.setId(t.getId());
        vo.setTitle(t.getTitle());
        vo.setDescription(t.getDescription());
        vo.setCategory(t.getCategory());
        vo.setCategoryLabel(SurveyCategory.of(t.getCategory()).getLabel());
        vo.setSourceSurveyId(t.getSourceSurveyId());
        vo.setQuestionCount(t.getQuestionCount());
        vo.setAnonymousDefault(t.getAnonymousDefault());
        vo.setLimitOnceDefault(t.getLimitOnceDefault());
        vo.setStatus(t.getStatus());
        vo.setStatusLabel(statusLabel(t.getStatus()));
        vo.setAuditRemark(t.getAuditRemark());
        vo.setAuditBy(t.getAuditBy());
        vo.setAuditTime(t.getAuditTime());
        vo.setSubmitTime(t.getSubmitTime());
        vo.setRatingCount(t.getRatingCount());
        vo.setRatingAvg(t.getRatingAvg() == null ? null
                : t.getRatingAvg().setScale(1, RoundingMode.HALF_UP));
        vo.setMyScore(myScore);
        vo.setUseCount(t.getUseCount());
        vo.setCreateBy(t.getCreateBy());
        vo.setCreateTime(t.getCreateTime());
        vo.setUpdateTime(t.getUpdateTime());
        vo.setStructure(structure);
        return vo;
    }

    /** 平均分保留 1 位小数（R-TPL-2）；无人评分返回 null，不返回 0 */
    public static BigDecimal avgOf(Integer count, Integer sum) {
        if (count == null || count == 0 || sum == null) {
            return null;
        }
        return BigDecimal.valueOf(sum)
                .divide(BigDecimal.valueOf(count), 1, RoundingMode.HALF_UP);
    }

    private static String statusLabel(String status) {
        TemplateStatus st = TemplateStatus.of(status);
        return st == null ? status : st.getLabel();
    }

    private static SurveyCategory parseCategory(String code) {
        SurveyCategory c = SurveyCategory.parse(code);
        if (c == null) {
            throw BizException.badRequest("未知的问卷分类：" + code);
        }
        return c;
    }

    private static Integer intOr(Integer v, Integer dft) {
        if (v == null) {
            return dft == null ? 0 : dft;
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
}
