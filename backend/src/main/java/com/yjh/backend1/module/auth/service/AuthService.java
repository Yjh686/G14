package com.yjh.backend1.module.auth.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yjh.backend1.common.BizException;
import com.yjh.backend1.common.PasswordPolicy;
import com.yjh.backend1.common.RequestUtil;
import com.yjh.backend1.module.config.service.ConfigService;
import com.yjh.backend1.module.auth.dto.ChangePasswordRequest;
import com.yjh.backend1.module.auth.dto.LoginRequest;
import com.yjh.backend1.module.auth.dto.LoginResponse;
import com.yjh.backend1.module.auth.dto.MeResponse;
import com.yjh.backend1.module.auth.dto.RegisterRequest;
import com.yjh.backend1.module.auth.dto.UpdateProfileRequest;
import com.yjh.backend1.module.log.entity.SysLoginLog;
import com.yjh.backend1.module.log.mapper.SysLoginLogMapper;
import com.yjh.backend1.module.rbac.entity.SysRole;
import com.yjh.backend1.module.rbac.entity.SysUserRole;
import com.yjh.backend1.module.rbac.mapper.SysRoleMapper;
import com.yjh.backend1.module.rbac.mapper.SysUserRoleMapper;
import com.yjh.backend1.module.rbac.service.PermissionService;
import com.yjh.backend1.module.user.entity.SysUser;
import com.yjh.backend1.module.user.mapper.SysUserMapper;
import com.yjh.backend1.security.JwtUtil;
import com.yjh.backend1.security.LoginUser;
import com.yjh.backend1.security.UserContext;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Set;

/**
 * 认证服务：登录、登出、注册、改密、个人资料。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final SysUserMapper userMapper;
    private final SysRoleMapper roleMapper;
    private final SysUserRoleMapper userRoleMapper;
    private final SysLoginLogMapper loginLogMapper;
    private final PermissionService permissionService;
    private final ConfigService configService;
    private final JwtUtil jwtUtil;
    private final BCryptPasswordEncoder passwordEncoder;

    @Value("${app.security.max-login-fail:5}")
    private int maxLoginFail;

    @Value("${app.security.lock-minutes:15}")
    private int lockMinutes;

    /** 账号不存在时也跑一次 BCrypt，避免用响应耗时探测账号是否存在 */
    private static final String DUMMY_HASH = "$2a$10$ycGRbaFEHWw7j0ISRRgIDO8EJR5dueAFCMqjChkOqVXWJgDRQyhjK";

    // =========================================================
    // 登录
    // =========================================================

    /**
     * 账号密码登录。
     * <p>
     * 失败处理：
     * <ul>
     *   <li>账号不存在 / 密码错误 → 统一提示"账号或密码错误"，真实原因写日志</li>
     *   <li>连续失败达上限 → 锁定 {@code lockMinutes} 分钟</li>
     *   <li>禁用 / 待审核 / 已驳回 → 明确提示，不占用失败次数</li>
     * </ul>
     */
    @Transactional(rollbackFor = Exception.class)
    public LoginResponse login(LoginRequest req, HttpServletRequest httpReq) {
        String username = req.getUsername().trim();
        String ip = RequestUtil.clientIp(httpReq);
        String ua = RequestUtil.userAgent(httpReq);

        SysUser user = userMapper.selectOne(
                Wrappers.<SysUser>lambdaQuery().eq(SysUser::getUsername, username));

        // --- 账号不存在 ---
        if (user == null) {
            passwordEncoder.matches(req.getPassword(), DUMMY_HASH);
            writeLoginLog(null, username, false, "账号不存在", ip, ua);
            throw BizException.unauthorized("账号或密码错误");
        }

        // --- 已锁定 ---
        LocalDateTime now = LocalDateTime.now();
        if (user.getLockedUntil() != null && user.getLockedUntil().isAfter(now)) {
            long minutes = java.time.Duration.between(now, user.getLockedUntil()).toMinutes() + 1;
            writeLoginLog(user.getId(), username, false, "账号已锁定", ip, ua);
            throw BizException.conflict("账号因多次登录失败已被锁定，请 " + minutes + " 分钟后再试");
        }

        // --- 状态检查（不占用失败次数） ---
        if (!Integer.valueOf(1).equals(user.getStatus())) {
            writeLoginLog(user.getId(), username, false, "账号已禁用", ip, ua);
            throw BizException.forbidden("账号已被禁用，请联系管理员");
        }
        if (Integer.valueOf(0).equals(user.getAuditStatus())) {
            writeLoginLog(user.getId(), username, false, "待审核", ip, ua);
            throw BizException.forbidden("账号正在等待管理员审核，请耐心等候");
        }
        if (Integer.valueOf(2).equals(user.getAuditStatus())) {
            writeLoginLog(user.getId(), username, false, "审核未通过", ip, ua);
            String reason = user.getAuditRemark() == null ? "" : "：" + user.getAuditRemark();
            throw BizException.forbidden("注册申请未通过审核" + reason);
        }

        // --- 密码校验 ---
        if (!passwordEncoder.matches(req.getPassword(), user.getPasswordHash())) {
            int fails = (user.getLoginFailCount() == null ? 0 : user.getLoginFailCount()) + 1;
            SysUser upd = new SysUser();
            upd.setId(user.getId());
            upd.setLoginFailCount(fails);
            String reason = "密码错误";
            if (fails >= maxLoginFail) {
                upd.setLockedUntil(now.plusMinutes(lockMinutes));
                upd.setLoginFailCount(0);
                reason = "密码错误，账号已锁定";
            }
            userMapper.updateById(upd);
            writeLoginLog(user.getId(), username, false, reason, ip, ua);

            if (fails >= maxLoginFail) {
                throw BizException.conflict("密码错误次数过多，账号已锁定 " + lockMinutes + " 分钟");
            }
            int remain = maxLoginFail - fails;
            throw BizException.unauthorized("账号或密码错误，还可尝试 " + remain + " 次");
        }

        // --- 登录成功 ---
        SysUser ok = new SysUser();
        ok.setId(user.getId());
        ok.setLoginFailCount(0);
        ok.setLockedUntil(null);
        ok.setLastLoginTime(now);
        userMapper.updateById(ok);

        if (Integer.valueOf(1).equals(user.getMustChangePassword())) {
            log.info("用户 {} 登录成功，但需强制改密", username);
        }
        writeLoginLog(user.getId(), username, true, null, ip, ua);

        int ver = user.getTokenVersion() == null ? 0 : user.getTokenVersion();
        String token = jwtUtil.generate(user.getId(), user.getUsername(), ver);

        PermissionService.Snapshot snap = permissionService.load(user.getId());
        // 权限缓存可能是旧的（比如管理员刚改过角色），这里 evict 一次保证登录后拿到最新
        permissionService.evict(user.getId());
        snap = permissionService.load(user.getId());

        return new LoginResponse(
                token,
                jwtUtil.getExpireSeconds(),
                user.getId(),
                user.getUsername(),
                user.getRealName(),
                snap.roleCode(),
                snap.roleName(),
                Integer.valueOf(1).equals(user.getMustChangePassword()),
                snap.permCodes()
        );
    }

    /**
     * 登出。
     * <p>
     * JWT 无状态，服务端无法真正"吊销"单个令牌；这里只记一条日志。
     * 真正的强制下线靠「改密 / 禁用 → token_version+1」。
     */
    public void logout() {
        LoginUser u = UserContext.require();
        log.info("用户 {} 登出", u.getUsername());
    }

    // =========================================================
    // 注册
    // =========================================================

    /**
     * 自助注册。账号进入待审核状态，不能立即登录。
     */
    @Transactional(rollbackFor = Exception.class)
    public void register(RegisterRequest req) {
        String username = req.getUsername().trim();
        // 密码规则来自系统配置（FR-CFG-02），管理员改过长度/复杂度后立即生效
        PasswordPolicy.check(req.getPassword(), configService.passwordRules());

        Long exists = userMapper.selectCount(
                Wrappers.<SysUser>lambdaQuery().eq(SysUser::getUsername, username));
        if (exists != null && exists > 0) {
            throw BizException.conflict("该学号/工号已被注册，请直接登录或联系管理员");
        }
        if (req.getPhone() != null && !req.getPhone().isBlank()) {
            Long phoneExists = userMapper.selectCount(
                    Wrappers.<SysUser>lambdaQuery().eq(SysUser::getPhone, req.getPhone().trim()));
            if (phoneExists != null && phoneExists > 0) {
                throw BizException.conflict("该手机号已被使用");
            }
        }

        String roleCode = "TEACHER".equalsIgnoreCase(req.getRole()) ? SysRole.TEACHER : SysRole.STUDENT;
        SysRole role = roleMapper.selectOne(
                Wrappers.<SysRole>lambdaQuery().eq(SysRole::getCode, roleCode));
        if (role == null) {
            throw BizException.badRequest("角色数据异常，请联系管理员");
        }

        SysUser u = new SysUser();
        u.setUsername(username);
        u.setPasswordHash(passwordEncoder.encode(req.getPassword()));
        u.setRealName(req.getRealName().trim());
        u.setStatus(1);
        u.setAuditStatus(0);          // 待审核
        u.setMustChangePassword(0);
        u.setPhone(blankToNull(req.getPhone()));
        u.setDepartment(blankToNull(req.getDepartment()));
        u.setTokenVersion(0);
        u.setLoginFailCount(0);
        userMapper.insert(u);

        SysUserRole ur = new SysUserRole();
        ur.setUserId(u.getId());
        ur.setRoleId(role.getId());
        userRoleMapper.insert(ur);

        log.info("新注册待审核 userId={} username={} role={}", u.getId(), username, roleCode);
    }

    // =========================================================
    // 改密
    // =========================================================

    /**
     * 修改自己的密码。成功后 token_version+1，其它设备上的旧令牌立即失效，
     * 并给当前设备返回新令牌，避免用户被迫重新登录。
     */
    @Transactional(rollbackFor = Exception.class)
    public LoginResponse changePassword(ChangePasswordRequest req) {
        Long userId = UserContext.requireUserId();
        SysUser user = userMapper.selectById(userId);
        if (user == null) {
            throw BizException.unauthorized("账号不存在");
        }

        if (!passwordEncoder.matches(req.getOldPassword(), user.getPasswordHash())) {
            throw BizException.badRequest("原密码不正确");
        }
        if (req.getOldPassword().equals(req.getNewPassword())) {
            throw BizException.badRequest("新密码不能与原密码相同");
        }
        PasswordPolicy.check(req.getNewPassword(), configService.passwordRules());

        // 原子更新：password_hash + token_version + must_change_password 一起改
        SysUser upd = new SysUser();
        upd.setId(userId);
        upd.setPasswordHash(passwordEncoder.encode(req.getNewPassword()));
        upd.setTokenVersion((user.getTokenVersion() == null ? 0 : user.getTokenVersion()) + 1);
        upd.setMustChangePassword(0);
        upd.setLoginFailCount(0);
        upd.setLockedUntil(null);
        userMapper.updateById(upd);

        permissionService.evict(userId);

        int newVer = upd.getTokenVersion();
        String token = jwtUtil.generate(userId, user.getUsername(), newVer);
        PermissionService.Snapshot snap = permissionService.load(userId);

        log.info("用户 {} 修改密码成功，tokenVersion -> {}", user.getUsername(), newVer);

        return new LoginResponse(
                token, jwtUtil.getExpireSeconds(),
                userId, user.getUsername(), user.getRealName(),
                snap.roleCode(), snap.roleName(),
                false, snap.permCodes()
        );
    }

    // =========================================================
    // 个人资料
    // =========================================================

    public MeResponse me() {
        LoginUser lu = UserContext.require();
        SysUser user = userMapper.selectById(lu.getUserId());
        if (user == null) {
            throw BizException.unauthorized("账号不存在");
        }
        MeResponse r = new MeResponse();
        r.setUserId(user.getId());
        r.setUsername(user.getUsername());
        r.setRealName(user.getRealName());
        r.setRoleCode(lu.getRoleCode());
        r.setRoleName(lu.getRoleName());
        r.setDepartment(user.getDepartment());
        r.setPhone(user.getPhone());
        r.setEmail(user.getEmail());
        r.setMustChangePassword(Integer.valueOf(1).equals(user.getMustChangePassword()));
        r.setLastLoginTime(user.getLastLoginTime());
        r.setPermissions(lu.getPermissions() == null ? Set.of() : lu.getPermissions());
        return r;
    }

    @Transactional(rollbackFor = Exception.class)
    public MeResponse updateProfile(UpdateProfileRequest req) {
        Long userId = UserContext.requireUserId();
        SysUser upd = new SysUser();
        upd.setId(userId);
        if (req.getRealName() != null && !req.getRealName().isBlank()) {
            upd.setRealName(req.getRealName().trim());
        }
        if (req.getPhone() != null) {
            String phone = req.getPhone().trim();
            if (!phone.isEmpty()) {
                Long cnt = userMapper.selectCount(
                        Wrappers.<SysUser>lambdaQuery()
                                .eq(SysUser::getPhone, phone)
                                .ne(SysUser::getId, userId));
                if (cnt != null && cnt > 0) {
                    throw BizException.conflict("该手机号已被其他账号使用");
                }
            }
            upd.setPhone(phone.isEmpty() ? null : phone);
        }
        if (req.getEmail() != null) {
            upd.setEmail(blankToNull(req.getEmail()));
        }
        if (req.getDepartment() != null) {
            upd.setDepartment(blankToNull(req.getDepartment()));
        }
        userMapper.updateById(upd);
        return me();
    }

    // =========================================================
    // 内部
    // =========================================================

    private void writeLoginLog(Long userId, String username, boolean success, String reason,
                               String ip, String ua) {
        SysLoginLog l = new SysLoginLog();
        l.setUserId(userId);
        l.setUsername(username);
        l.setResult(success ? 1 : 0);
        l.setReason(reason);
        l.setIp(ip);
        l.setUserAgent(ua);
        l.setCreateTime(LocalDateTime.now());
        loginLogMapper.insert(l);
    }

    private static String blankToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
