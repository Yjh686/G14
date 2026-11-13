package com.yjh.backend1.module.recovery.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yjh.backend1.common.BizException;
import com.yjh.backend1.common.RequestUtil;
import com.yjh.backend1.module.config.service.ConfigService;
import com.yjh.backend1.module.log.service.OperationLogService;
import com.yjh.backend1.module.recovery.dto.RecoveryLookupRequest;
import com.yjh.backend1.module.recovery.dto.RecoveryQuestionsVO;
import com.yjh.backend1.module.recovery.dto.RecoveryResetRequest;
import com.yjh.backend1.module.recovery.dto.RecoverySetupRequest;
import com.yjh.backend1.module.recovery.entity.SysRecoveryLog;
import com.yjh.backend1.module.recovery.entity.SysUserRecovery;
import com.yjh.backend1.module.recovery.mapper.SysRecoveryLogMapper;
import com.yjh.backend1.module.recovery.mapper.SysUserRecoveryMapper;
import com.yjh.backend1.module.user.entity.SysUser;
import com.yjh.backend1.module.user.mapper.SysUserMapper;
import com.yjh.backend1.security.UserContext;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

/**
 * 密保问题自助找回密码。
 *
 * <h3>为什么用密保问题，而不是邮箱/短信验证码</h3>
 * 邮箱要 SMTP 服务器、短信要付费网关且模板审核 1~2 天；更要紧的是，
 * 校园场景里学生邮箱多半没人看、手机号经常换，而「你的宿舍号是多少」
 * 这类只有本人答得上的问题，反而是最可靠的凭据。代价是答案可被熟人猜到，
 * 所以配了失败锁定 + 全量流水，并且管理员重置始终作为兜底存在。
 *
 * <h3>这条通道的风险点（设计时逐条处理）</h3>
 * <ol>
 *   <li><b>账号枚举</b>：查密保问题必须能区分「账号不存在」与「没设置密保」，
 *       否则这东西没法用。取舍是<b>把可枚举的信息换成「知道了也没用」</b>——
 *       看到的只有两个问题文本，问题和答案都是本人才有意义的。
 *       另外账号不存在时照样跑一次 BCrypt，避免用耗时差探测。</li>
 *   <li><b>暴力猜答案</b>：失败计数与锁定独立于 sys_user 的登录失败计数。
 *       混在一起的话，有人拿别人学号乱试密保，会把那个人正常登录也锁掉。</li>
 *   <li><b>知道答案就能直接改密</b>：这是本功能的定义，不是缺陷。但改密后
 *       {@code token_version + 1}，攻击者即便之前偷到过令牌也立刻失效。</li>
 *   <li><b>两个答案答对一个能过</b>：不允许。任一答案错误都算失败并计数，
 *       否则「猜中一半」就能过关，等于把人人都知道的那个问题变成万能钥匙。</li>
 * </ol>
 *
 * <h3>写操作全部委托给 {@link RecoveryWriteService}</h3>
 * 这不是为了拆文件好看。最初「失败计数 + 抛出 400」写在同一个
 * {@code @Transactional} 方法里，实测锁定<b>从未触发</b>：BizException 是
 * RuntimeException，抛出后事务回滚，刚写入的计数跟着没了，而错误提示看起来
 * 完全正常（永远「还可尝试 4 次」），从接口层面根本看不出问题。
 * 现在 {@code recordFailure} 用 REQUIRES_NEW 独立提交，抛异常也留得住。
 *
 * <h3>答案的比对方式</h3>
 * 统一 {@code trim} + 小写 + 全角转半角后再 BCrypt 比对。中文答案的大小写
 * 无关痛痒，但英文答案（"Building 3" / "building 3"）如果区分大小写，
 * 用户会反复答错并被锁定，那属于产品缺陷而不是安全特性。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RecoveryService {

    private final SysUserRecoveryMapper recoveryMapper;
    private final SysRecoveryLogMapper recoveryLogMapper;
    private final SysUserMapper userMapper;
    private final ConfigService configService;
    private final OperationLogService operationLogService;
    private final RecoveryWriteService writeService;
    private final BCryptPasswordEncoder passwordEncoder;

    /** 账号不存在时也跑一次 BCrypt，抹平「查得到答案 vs 查不到」的耗时差 */
    private static final String DUMMY_HASH = "$2a$10$ycGRbaFEHWw7j0ISRRgIDO8EJR5dueAFCMqjChkOqVXWJgDRQyhjK";

    private static final int QUESTION_COUNT = 2;
    private static final int MAX_QUESTION_LEN = 120;
    private static final int MAX_FAIL_LOW = 3;
    private static final int MAX_FAIL_HIGH = 10;
    private static final int LOCK_MIN_LOW = 5;
    private static final int LOCK_MIN_HIGH = 1440;

    // =========================================================
    // 第一步：用账号换密保问题（免登录）
    // =========================================================

    /**
     * 查询某账号的密保问题。
     * <p>
     * 会明确区分几种失败，因为它们的「下一步该做什么」完全不同：
     * 账号不存在 → 去找老师核对学号；没设置密保 → 去找管理员重置；
     * 被锁定 → 等一会儿再来。把三者合并成「查不到」只会让人反复试。
     */
    public RecoveryQuestionsVO lookup(RecoveryLookupRequest req, HttpServletRequest httpReq) {
        String username = req.getUsername().trim();
        requireEnabled();

        SysUser user = userMapper.selectOne(
                Wrappers.<SysUser>lambdaQuery().eq(SysUser::getUsername, username));
        if (user == null) {
            passwordEncoder.matches("x", DUMMY_HASH);
            writeLog(null, username, "LOOKUP", false, "账号不存在", httpReq);
            throw BizException.notFound("该学号/工号不存在，请核对后重试");
        }
        if (!Integer.valueOf(1).equals(user.getStatus())) {
            writeLog(user.getId(), username, "LOOKUP", false, "账号已禁用", httpReq);
            throw BizException.forbidden("该账号已被禁用，无法自助找回，请联系管理员");
        }
        if (!Integer.valueOf(1).equals(user.getAuditStatus())) {
            writeLog(user.getId(), username, "LOOKUP", false, "账号未通过审核", httpReq);
            throw BizException.forbidden("该账号尚未通过审核，暂不支持自助找回");
        }

        SysUserRecovery rec = findByUserId(user.getId());
        if (rec == null) {
            passwordEncoder.matches("x", DUMMY_HASH);
            writeLog(user.getId(), username, "LOOKUP", false, "未设置密保问题", httpReq);
            throw BizException.notFound("该账号还没有设置密保问题，请联系管理员重置密码");
        }
        if (rec.lockedNow()) {
            long minutes = java.time.Duration.between(LocalDateTime.now(), rec.getLockedUntil()).toMinutes() + 1;
            writeLog(user.getId(), username, "LOOKUP", false, "验证已锁定", httpReq);
            throw BizException.conflict("密保验证失败次数过多，请 " + minutes + " 分钟后再试");
        }

        writeLog(user.getId(), username, "LOOKUP", true, null, httpReq);
        return new RecoveryQuestionsVO(user.getUsername(), rec.getQuestion1(), rec.getQuestion2());
    }

    // =========================================================
    // 第二步：答题并重设密码（免登录）
    // =========================================================

    /**
     * 校验两个答案并重设密码。
     * <p>
     * <b>刻意不加 {@code @Transactional}</b>：这个方法的主要出口是「答案错误
     * 并抛 400」，而失败计数必须留下来。挂了事务的话计数会被一起回滚，锁定
     * 永远不生效。成功路径的原子性由 {@link RecoveryWriteService#applyReset}
     * 自己保证（密码、令牌版本、密保计数在同一个事务里）。
     */
    public void reset(RecoveryResetRequest req, HttpServletRequest httpReq) {
        String username = req.getUsername().trim();
        requireEnabled();

        SysUser user = userMapper.selectOne(
                Wrappers.<SysUser>lambdaQuery().eq(SysUser::getUsername, username));
        if (user == null) {
            passwordEncoder.matches("x", DUMMY_HASH);
            writeLog(null, username, "RESET", false, "账号不存在", httpReq);
            throw BizException.notFound("该学号/工号不存在，请核对后重试");
        }
        if (!Integer.valueOf(1).equals(user.getStatus())) {
            writeLog(user.getId(), username, "RESET", false, "账号已禁用", httpReq);
            throw BizException.forbidden("该账号已被禁用，无法自助找回，请联系管理员");
        }

        SysUserRecovery rec = findByUserId(user.getId());
        if (rec == null) {
            passwordEncoder.matches("x", DUMMY_HASH);
            writeLog(user.getId(), username, "RESET", false, "未设置密保问题", httpReq);
            throw BizException.notFound("该账号还没有设置密保问题，请联系管理员重置密码");
        }

        // --- 锁定检查放在验答案之前：锁定期间不该白跑两次 BCrypt ---
        if (rec.lockedNow()) {
            long minutes = java.time.Duration.between(LocalDateTime.now(), rec.getLockedUntil()).toMinutes() + 1;
            writeLog(user.getId(), username, "VERIFY", false, "验证已锁定", httpReq);
            throw BizException.conflict("密保验证失败次数过多，请 " + minutes + " 分钟后再试");
        }

        // --- 两个答案都必须对 ---
        boolean ok1 = answerMatches(req.getAnswer1(), rec.getAnswerHash1());
        boolean ok2 = answerMatches(req.getAnswer2(), rec.getAnswerHash2());
        if (!ok1 || !ok2) {
            int maxFail = maxFail();
            int remain = writeService.recordFailure(rec.getId(), maxFail, lockMinutes());
            if (remain <= 0) {
                writeLog(user.getId(), username, "VERIFY", false, "答案错误，已锁定", httpReq);
                throw BizException.conflict("密保答案错误次数过多，账号已锁定 " + lockMinutes() + " 分钟");
            }
            writeLog(user.getId(), username, "VERIFY", false,
                    "答案错误（第 " + (maxFail - remain) + " 次）", httpReq);
            throw BizException.badRequest("密保答案不正确，还可尝试 " + remain + " 次");
        }

        // --- 答案正确：先校验新密码规则 ---
        // 顺序刻意如此：密码不合规是用户的输入问题，不该消耗「猜答案」的额度，
        // 否则连试几个弱密码就把自己锁了，而每一步其实都答对了。
        writeService.checkPasswordPolicy(req.getNewPassword());

        if (writeService.sameAsCurrent(user.getId(), req.getNewPassword())) {
            throw BizException.badRequest("新密码不能与原密码相同");
        }

        writeService.applyReset(user.getId(), rec.getId(), req.getNewPassword());

        writeLog(user.getId(), username, "RESET", true, null, httpReq);
        operationLogService.write("账号安全", "密保找回密码", "sys_user", user.getId(),
                "用户《" + user.getUsername() + "》通过密保问题自助重置了密码", httpReq);
    }

    // =========================================================
    // 设置 / 修改自己的密保问题（需登录）
    // =========================================================

    /**
     * 设置或修改密保问题。一个人只能有一套（两问两答），重复设置即覆盖。
     * <p>
     * 两个问题不允许完全相同：题面一样、答案也一样时，第二个问题等于没设，
     * 而失败计数是按「两次都答对」判定的，重复问题会让人误判自己的余量。
     */
    @Transactional(rollbackFor = Exception.class)
    public void setup(RecoverySetupRequest req, HttpServletRequest httpReq) {
        Long userId = UserContext.requireUserId();
        SysUser user = userMapper.selectById(userId);
        if (user == null) {
            throw BizException.unauthorized("账号不存在");
        }

        List<RecoverySetupRequest.QuestionAnswer> items = req.getItems();
        if (items == null || items.size() != QUESTION_COUNT) {
            throw BizException.badRequest("必须设置 2 个密保问题");
        }
        RecoverySetupRequest.QuestionAnswer a = items.get(0);
        RecoverySetupRequest.QuestionAnswer b = items.get(1);

        String q1 = a.getQuestion().trim();
        String q2 = b.getQuestion().trim();
        String s1 = normalizeAnswer(a.getAnswer());
        String s2 = normalizeAnswer(b.getAnswer());

        if (q1.length() > MAX_QUESTION_LEN || q2.length() > MAX_QUESTION_LEN) {
            throw BizException.badRequest("密保问题不能超过 " + MAX_QUESTION_LEN + " 个字");
        }
        if (q1.equalsIgnoreCase(q2)) {
            throw BizException.badRequest("两个密保问题不能相同");
        }
        if (s1.equalsIgnoreCase(s2)) {
            throw BizException.badRequest("两个密保答案不能相同");
        }
        if (s1.length() < 2) {
            throw BizException.badRequest("密保答案至少 2 个字符");
        }

        SysUserRecovery exist = findByUserId(userId);
        if (exist == null) {
            SysUserRecovery rec = new SysUserRecovery();
            rec.setUserId(userId);
            rec.setQuestion1(q1);
            rec.setAnswerHash1(passwordEncoder.encode(s1));
            rec.setQuestion2(q2);
            rec.setAnswerHash2(passwordEncoder.encode(s2));
            rec.setFailCount(0);
            recoveryMapper.insert(rec);
        } else {
            SysUserRecovery upd = new SysUserRecovery();
            upd.setId(exist.getId());
            upd.setQuestion1(q1);
            upd.setAnswerHash1(passwordEncoder.encode(s1));
            upd.setQuestion2(q2);
            upd.setAnswerHash2(passwordEncoder.encode(s2));
            // 换了密保就把之前的失败计数清零：旧答案的失败记录对新答案没有意义
            upd.setFailCount(0);
            upd.setLockedUntil(null);
            recoveryMapper.updateById(upd);
        }

        operationLogService.write("账号安全", "设置密保问题", "sys_user", userId,
                "用户《" + user.getUsername() + "》更新了密保问题", httpReq);
    }

    /** 当前用户是否已设置密保问题（前端据此决定显示「设置密保」还是「修改密保」） */
    public boolean hasRecovery() {
        Long userId = UserContext.requireUserId();
        return findByUserId(userId) != null;
    }

    /** 当前用户已设置的密保问题题面（需登录，供个人资料页回显；不回答案） */
    public RecoveryQuestionsVO myQuestions() {
        Long userId = UserContext.requireUserId();
        SysUser user = userMapper.selectById(userId);
        SysUserRecovery rec = findByUserId(userId);
        if (rec == null) {
            throw BizException.notFound("还没有设置密保问题");
        }
        return new RecoveryQuestionsVO(user == null ? null : user.getUsername(),
                rec.getQuestion1(), rec.getQuestion2());
    }

    // =========================================================
    // 内部
    // =========================================================

    private SysUserRecovery findByUserId(Long userId) {
        return recoveryMapper.selectOne(
                Wrappers.<SysUserRecovery>lambdaQuery().eq(SysUserRecovery::getUserId, userId));
    }

    /**
     * 答案归一化后再比对。
     * <p>
     * trim + 小写 + 全角转半角：用户答「Ｂｕｉｌｄｉｎｇ　３」和「building 3」时
     * 应该判为同一个答案。中文不受影响，但英文答案如果严格区分大小写，
     * 用户会反复答错并被锁定 —— 那属于产品缺陷，不是安全特性。
     */
    private boolean answerMatches(String raw, String hash) {
        if (raw == null || hash == null) {
            return false;
        }
        String norm = normalizeAnswer(raw);
        if (norm.isEmpty()) {
            // 空答案照样跑一次 BCrypt，保持耗时一致
            passwordEncoder.matches("x", DUMMY_HASH);
            return false;
        }
        return passwordEncoder.matches(norm, hash);
    }

    private static String normalizeAnswer(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(raw.length());
        for (char c : raw.toCharArray()) {
            // 全角 ASCII（！到 ～）转半角
            if (c >= '\uFF01' && c <= '\uFF5E') {
                sb.append((char) (c - 0xFEE0));
            } else if (c == '\u3000') {
                // 全角空格转半角
                sb.append(' ');
            } else {
                sb.append(c);
            }
        }
        return sb.toString().trim().toLowerCase(Locale.ROOT);
    }

    private void requireEnabled() {
        if (!configService.getBool("security.recovery.enabled", true)) {
            throw BizException.forbidden("管理员已关闭自助找回密码，请联系老师或管理员重置");
        }
    }

    private int maxFail() {
        return clamp(configService.getInt("security.recovery.max-fail", 5), MAX_FAIL_LOW, MAX_FAIL_HIGH);
    }

    private int lockMinutes() {
        return clamp(configService.getInt("security.recovery.lock-minutes", 30), LOCK_MIN_LOW, LOCK_MIN_HIGH);
    }

    /** 越界值夹到合法区间而非报错：管理员填错不该让整条通道不可用 */
    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private void writeLog(Long userId, String username, String step, boolean success,
                          String reason, HttpServletRequest req) {
        try {
            SysRecoveryLog l = new SysRecoveryLog();
            l.setUserId(userId);
            l.setUsername(username);
            l.setStep(step);
            l.setResult(success ? 1 : 0);
            l.setReason(reason);
            l.setIp(RequestUtil.clientIp(req));
            l.setCreateTime(LocalDateTime.now());
            recoveryLogMapper.insert(l);
        } catch (Exception e) {
            // 与 OperationLogService 同口径：日志写不进去不影响主流程
            log.warn("写找回日志失败 step={} username={}: {}", step, username, e.getMessage());
        }
    }
}
