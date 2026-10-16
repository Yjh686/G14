package com.g14.g14.login.service;

import com.g14.g14.login.common.BizException;
import com.g14.g14.login.common.PasswordPolicy;
import com.g14.g14.login.common.ResultCode;
import com.g14.g14.login.dto.ChangePasswordRequest;
import com.g14.g14.login.dto.LoginRequest;
import com.g14.g14.login.dto.LoginResponse;
import com.g14.g14.login.dto.UserVO;
import com.g14.g14.login.entity.LoginLog;
import com.g14.g14.login.entity.User;
import com.g14.g14.login.mapper.LoginLogMapper;
import com.g14.g14.login.mapper.UserMapper;
import com.g14.g14.login.security.AuthContext;
import com.g14.g14.login.security.JwtUtil;
import com.g14.g14.login.security.LoginUser;
import com.g14.g14.login.security.TokenBlacklist;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import javax.annotation.PostConstruct;
import javax.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.regex.Pattern;

/**
 * 认证服务：登录、登出、获取当前用户、修改密码。
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    /** 中国大陆手机号 */
    private static final Pattern PHONE_PATTERN = Pattern.compile("^1[3-9]\\d{9}$");

    private static final String LOGIN_TYPE_ACCOUNT = "ACCOUNT";
    private static final String LOGIN_TYPE_PHONE = "PHONE";

    private final UserMapper userMapper;
    private final LoginLogMapper loginLogMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final TokenBlacklist tokenBlacklist;

    @Value("${app.login.max-fail:5}")
    private int maxFail;

    @Value("${app.login.lock-minutes:15}")
    private int lockMinutes;

    /**
     * 用于「账号不存在」时执行一次等价耗时的 BCrypt 校验，
     * 消除响应时间差异，防止攻击者通过计时侧信道枚举系统内存在的账号。
     */
    private String timingEqualizationHash;

    public AuthService(UserMapper userMapper,
                       LoginLogMapper loginLogMapper,
                       PasswordEncoder passwordEncoder,
                       JwtUtil jwtUtil,
                       TokenBlacklist tokenBlacklist) {
        this.userMapper = userMapper;
        this.loginLogMapper = loginLogMapper;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtil = jwtUtil;
        this.tokenBlacklist = tokenBlacklist;
    }

    @PostConstruct
    public void init() {
        this.timingEqualizationHash = passwordEncoder.encode("timing-equalization-dummy");
    }

    /* ================================================================== */
    /*  登录                                                               */
    /* ================================================================== */

    /**
     * 登录。
     *
     * <p>流程：账号定位 → 锁定检查 → 禁用检查 → BCrypt 校验 → 失败计数 → 签发 JWT。
     * <p>角色由后端查库识别（前端不提交 role），手机号登录仅对老师 / 管理员开放。
     *
     * <p><b>事务语义</b>：登录失败计数必须在抛出业务异常时依然落库，因此显式声明
     * {@code noRollbackFor = BizException.class}。若沿用默认的
     * {@code rollbackFor = Exception.class}，则「密码错误」异常会把
     * {@code onLoginFail} 一起回滚，连续失败锁定策略将彻底失效。
     */
    @Transactional(rollbackFor = Exception.class, noRollbackFor = BizException.class)
    public LoginResponse login(LoginRequest request, HttpServletRequest httpRequest) {
        String account = request.getAccount() == null ? "" : request.getAccount().trim();

        // 1) 定位用户：先按账号（学号 / 工号 / admin）查，未命中且形如手机号时再按手机号查
        User user = userMapper.selectByAccount(account);
        String loginType = LOGIN_TYPE_ACCOUNT;
        if (user == null && PHONE_PATTERN.matcher(account).matches()) {
            User byPhone = userMapper.selectByPhone(account);
            // 需求：学生用学号登录，只有老师 / 管理员可用手机号登录
            if (byPhone != null && byPhone.phoneLoginAllowed()) {
                user = byPhone;
                loginType = LOGIN_TYPE_PHONE;
            }
        }

        // 2) 账号不存在：执行一次等价耗时的哈希校验后，返回与「密码错误」完全一致的提示
        if (user == null) {
            passwordEncoder.matches(request.getPassword(), timingEqualizationHash);
            recordLoginLog(null, account, loginType, 0, "账号不存在", httpRequest);
            throw new BizException(ResultCode.BAD_CREDENTIALS);
        }

        // 3) 防爆破锁定
        if (user.locked()) {
            long remain = Math.max(1,
                    Duration.between(LocalDateTime.now(), user.getLockedUntil()).toMinutes() + 1);
            recordLoginLog(user.getId(), account, loginType, 0, "账号锁定中", httpRequest);
            throw new BizException(ResultCode.ACCOUNT_LOCKED,
                    "密码错误次数过多，账号已锁定，请 " + remain + " 分钟后重试");
        }

        // 4) 禁用检查
        if (!user.enabled()) {
            recordLoginLog(user.getId(), account, loginType, 0, "账号已禁用", httpRequest);
            throw new BizException(ResultCode.ACCOUNT_DISABLED);
        }

        // 5) 密码校验
        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            int failCount = (user.getLoginFailCount() == null ? 0 : user.getLoginFailCount()) + 1;
            LocalDateTime lockedUntil = null;
            if (failCount >= maxFail) {
                lockedUntil = LocalDateTime.now().plusMinutes(lockMinutes);
            }
            userMapper.onLoginFail(user.getId(), failCount, lockedUntil);
            recordLoginLog(user.getId(), account, loginType, 0,
                    "密码错误(" + failCount + "/" + maxFail + ")", httpRequest);

            if (lockedUntil != null) {
                throw new BizException(ResultCode.ACCOUNT_LOCKED,
                        "密码连续错误 " + maxFail + " 次，账号已锁定 " + lockMinutes + " 分钟");
            }
            // 文案必须与 ResultCode.BAD_CREDENTIALS 默认值逐字一致（前端契约断言
            // message === '账号或密码错误'）。剩余尝试次数只写审计日志，不外泄到响应。
            throw new BizException(ResultCode.BAD_CREDENTIALS);
        }

        // 6) 登录成功：清零失败计数、记录最后登录时间
        LocalDateTime now = LocalDateTime.now();
        userMapper.onLoginSuccess(user.getId(), now);
        user.setLoginFailCount(0);
        user.setLockedUntil(null);
        user.setLastLoginTime(now);
        recordLoginLog(user.getId(), account, loginType, 1, null, httpRequest);

        // 7) 签发 JWT（载荷含用户 ID 与角色）
        String token = jwtUtil.generate(user);
        log.info("用户登录成功: id={}, account={}, role={}, loginType={}, mustChangePassword={}",
                user.getId(), user.getAccount(), user.getRole(), loginType, user.requiresPasswordChange());

        return new LoginResponse(token, JwtUtil.TOKEN_TYPE, jwtUtil.getExpireSeconds(),
                user.requiresPasswordChange(), UserVO.from(user));
    }

    /* ================================================================== */
    /*  登出                                                               */
    /* ================================================================== */

    /**
     * 登出：把当前令牌的 jti 加入黑名单，使其在剩余有效期内立即失效。
     */
    public void logout() {
        LoginUser current = AuthContext.require();
        tokenBlacklist.add(current.getJti(), current.getTokenExpireAt());
        log.info("用户登出: id={}, account={}, jti={}",
                current.getUserId(), current.getAccount(), current.getJti());
    }

    /* ================================================================== */
    /*  当前用户                                                           */
    /* ================================================================== */

    public UserVO currentUser() {
        Long userId = AuthContext.require().getUserId();
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BizException(ResultCode.USER_NOT_FOUND);
        }
        return UserVO.from(user);
    }

    /* ================================================================== */
    /*  修改密码                                                           */
    /* ================================================================== */

    /**
     * 用户自助修改密码（首次登录强制改密复用本方法）。
     *
     * <p>关键点：
     * <ol>
     *   <li>必须校验原密码，防止令牌泄露后被直接接管账号；</li>
     *   <li>新密码不得与原密码相同（400/40003），且需通过强度与弱口令校验（400/40001）；</li>
     *   <li>成功后 {@code token_version + 1} → 该用户<b>所有</b>旧令牌立即失效（401/40103）；</li>
     *   <li>同时把当前令牌加入黑名单，并回签新令牌，避免前端被强制踢出。</li>
     * </ol>
     */
    @Transactional(rollbackFor = Exception.class)
    public LoginResponse changePassword(ChangePasswordRequest request) {
        LoginUser current = AuthContext.require();
        User user = userMapper.selectById(current.getUserId());
        if (user == null) {
            throw new BizException(ResultCode.USER_NOT_FOUND);
        }

        if (!passwordEncoder.matches(request.getOldPassword(), user.getPasswordHash())) {
            throw new BizException(ResultCode.OLD_PASSWORD_WRONG);
        }
        if (request.getNewPassword().equals(request.getOldPassword())) {
            throw new BizException(ResultCode.PASSWORD_SAME_AS_OLD);
        }
        PasswordPolicy.validate(request.getNewPassword());

        int newVersion = (user.getTokenVersion() == null ? 0 : user.getTokenVersion()) + 1;
        userMapper.updatePassword(user.getId(), passwordEncoder.encode(request.getNewPassword()),
                newVersion, 0);

        // 双保险：既推进 token_version（让其他设备的令牌失效），也拉黑当前令牌
        tokenBlacklist.add(current.getJti(), current.getTokenExpireAt());

        User fresh = userMapper.selectById(user.getId());
        String newToken = jwtUtil.generate(fresh);
        log.info("用户修改密码成功: id={}, account={}, tokenVersion={}",
                fresh.getId(), fresh.getAccount(), fresh.getTokenVersion());

        return new LoginResponse(newToken, JwtUtil.TOKEN_TYPE, jwtUtil.getExpireSeconds(),
                false, UserVO.from(fresh));
    }

    /* ================================================================== */

    private void recordLoginLog(Long userId, String account, String loginType,
                                int result, String reason, HttpServletRequest request) {
        try {
            LoginLog entity = new LoginLog();
            entity.setUserId(userId);
            entity.setAccount(account);
            entity.setLoginType(loginType);
            entity.setResult(result);
            entity.setReason(reason);
            entity.setIp(resolveIp(request));
            String ua = request == null ? null : request.getHeader("User-Agent");
            entity.setUserAgent(ua != null && ua.length() > 255 ? ua.substring(0, 255) : ua);
            loginLogMapper.insert(entity);
        } catch (Exception e) {
            // 审计日志写入失败不应影响主流程
            log.warn("登录日志写入失败: {}", e.getMessage());
        }
    }

    private String resolveIp(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String[] headers = {"X-Forwarded-For", "X-Real-IP", "Proxy-Client-IP", "WL-Proxy-Client-IP"};
        for (String header : headers) {
            String value = request.getHeader(header);
            if (StringUtils.hasText(value) && !"unknown".equalsIgnoreCase(value)) {
                int comma = value.indexOf(',');
                return comma > 0 ? value.substring(0, comma).trim() : value.trim();
            }
        }
        return request.getRemoteAddr();
    }
}
