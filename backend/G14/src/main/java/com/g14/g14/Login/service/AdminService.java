package com.g14.g14.login.service;

import com.g14.g14.login.common.BizException;
import com.g14.g14.login.common.PasswordPolicy;
import com.g14.g14.login.common.ResultCode;
import com.g14.g14.login.dto.CreateUserRequest;
import com.g14.g14.login.dto.PageResult;
import com.g14.g14.login.dto.UserQuery;
import com.g14.g14.login.dto.UserVO;
import com.g14.g14.login.entity.User;
import com.g14.g14.login.enums.RoleEnum;
import com.g14.g14.login.mapper.UserMapper;
import com.g14.g14.login.security.AuthContext;
import com.g14.g14.login.security.LoginUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 管理员服务：开通账号、重置密码、启禁用账号、用户列表。
 *
 * <p>所有接口由 {@code AdminController} 上的 {@code @RequireRole(ADMIN)} 统一保护。
 */
@Service
public class AdminService {

    private static final Logger log = LoggerFactory.getLogger(AdminService.class);

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;

    /** 系统初始密码。管理员不指定密码时使用（如 tools/seed-demo.mjs 传 initialPassword: null）。 */
    @Value("${app.auth.initial-password}")
    private String initialPassword;

    public AdminService(UserMapper userMapper, PasswordEncoder passwordEncoder) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
    }

    /* ================================================================== */
    /*  开通账号                                                           */
    /* ================================================================== */

    /**
     * 开通账号。
     *
     * <p>密码来源分两种：
     * <ul>
     *   <li>请求显式指定 → 套用<b>完整</b>强度策略与弱口令黑名单；</li>
     *   <li>请求传 null → 使用配置项 {@code app.auth.initial-password}，只校验长度。
     *       这是必要的宽松分支：系统初始密码本身在弱口令黑名单里，套用完整策略会自相矛盾。</li>
     * </ul>
     * 无论哪种情况，新账号都置 {@code must_change_password=1}，首次登录强制改密。
     */
    @Transactional(rollbackFor = Exception.class)
    public UserVO createUser(CreateUserRequest request) {
        RoleEnum role = RoleEnum.parse(request.getRole());
        if (role == null) {
            throw new BizException(ResultCode.PARAM_ERROR, "role 只能是 STUDENT / TEACHER / ADMIN");
        }

        String account = request.getAccount().trim();
        if (userMapper.countByAccount(account) > 0) {
            throw new BizException(ResultCode.ACCOUNT_EXISTS);
        }

        String phone = StringUtils.hasText(request.getPhone()) ? request.getPhone().trim() : null;
        if (phone != null && userMapper.countByPhone(phone) > 0) {
            throw new BizException(ResultCode.PHONE_EXISTS);
        }

        boolean explicit = StringUtils.hasText(request.getInitialPassword());
        String rawPassword = explicit ? request.getInitialPassword() : initialPassword;
        if (explicit) {
            PasswordPolicy.validate(rawPassword);
        } else {
            PasswordPolicy.validateLengthOnly(rawPassword);
        }

        User user = new User();
        user.setAccount(account);
        user.setRealName(request.getRealName().trim());
        user.setRole(role);
        user.setStatus(1);
        user.setPhone(phone);
        user.setPasswordHash(passwordEncoder.encode(rawPassword));
        user.setMustChangePassword(1);
        user.setTokenVersion(0);
        userMapper.insert(user);

        log.info("管理员开通账号: id={}, account={}, role={}", user.getId(), account, role);

        User created = userMapper.selectById(user.getId());
        return UserVO.from(created != null ? created : user);
    }

    /* ================================================================== */
    /*  重置密码                                                           */
    /* ================================================================== */

    /**
     * 管理员重置指定用户密码。
     *
     * <p>效果：密码被覆盖 → {@code token_version + 1}（该用户所有已签发令牌立即失效）
     * → {@code must_change_password = 1}（下次登录强制改密）。
     *
     * <p>{@code newPassword} 传 null 表示「恢复系统初始密码」，只做长度校验。
     */
    @Transactional(rollbackFor = Exception.class)
    public void resetPassword(Long userId, String newPassword) {
        User user = requireUser(userId);
        // 纵深防御：前端已按角色禁用按钮，后端仍必须独立校验，
        // 否则持有管理员令牌的攻击者可以重置其他管理员、从而横向接管系统。
        guardAdminOperation(user, "重置密码");

        boolean explicit = StringUtils.hasText(newPassword);
        String rawPassword = explicit ? newPassword : initialPassword;
        if (explicit) {
            PasswordPolicy.validate(rawPassword);
        } else {
            PasswordPolicy.validateLengthOnly(rawPassword);
        }

        int newVersion = (user.getTokenVersion() == null ? 0 : user.getTokenVersion()) + 1;
        userMapper.updatePassword(userId, passwordEncoder.encode(rawPassword), newVersion, 1);

        log.info("管理员重置密码: id={}, account={}, tokenVersion={}, explicit={}",
                userId, user.getAccount(), newVersion, explicit);
    }

    /* ================================================================== */
    /*  启用 / 禁用                                                        */
    /* ================================================================== */

    /**
     * 变更账号状态。禁用时同步推进 token_version，
     * 且鉴权中间件会因 {@code status != 1} 直接返回 403/40301，使在线令牌立即失效。
     */
    @Transactional(rollbackFor = Exception.class)
    public UserVO updateStatus(Long userId, boolean enabled) {
        User user = requireUser(userId);
        guardAdminOperation(user, "变更状态");
        int status = enabled ? 1 : 0;
        int newVersion = (user.getTokenVersion() == null ? 0 : user.getTokenVersion()) + 1;
        userMapper.updateStatus(userId, status, newVersion);
        log.info("管理员变更账号状态: id={}, account={}, status={}", userId, user.getAccount(), status);
        return UserVO.from(userMapper.selectById(userId));
    }

    /* ================================================================== */
    /*  用户列表                                                           */
    /* ================================================================== */

    public PageResult<UserVO> listUsers(UserQuery query) {
        String role = StringUtils.hasText(query.getRole()) ? query.getRole().trim().toUpperCase() : null;
        if (role != null && RoleEnum.parse(role) == null) {
            throw new BizException(ResultCode.PARAM_ERROR, "role 只能是 STUDENT / TEACHER / ADMIN");
        }
        String keyword = StringUtils.hasText(query.getKeyword()) ? query.getKeyword().trim() : null;

        long total = userMapper.countPage(keyword, role, query.getStatus());
        List<User> users = total == 0
                ? Collections.emptyList()
                : userMapper.selectPage(keyword, role, query.getStatus(), query.getOffset(),
                        query.getEffectivePageSize());

        List<UserVO> list = users.stream().map(UserVO::from).collect(Collectors.toList());
        return new PageResult<>(total, query.getEffectivePageNum(), query.getEffectivePageSize(), list);
    }

    /* ================================================================== */

    private User requireUser(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BizException(ResultCode.USER_NOT_FOUND);
        }
        return user;
    }

    /**
     * 管理员账号的保护规则（对应前端 {@code UserManage.vue} 中按钮禁用逻辑的后端兜底）：
     * <ul>
     *   <li>不能对<b>当前登录的自己</b>执行 → 403/40304（避免把自己锁在系统外）；</li>
     *   <li>不能对<b>其他管理员</b>执行 → 403/40305（避免管理员之间互相接管）。</li>
     * </ul>
     * 注意：管理员改自己的密码请走 {@code POST /api/auth/password}，而不是重置接口 ——
     * 重置接口不需要原密码，允许重置自己等于把「知道原密码」这道关卡绕过去了。
     */
    private void guardAdminOperation(User target, String action) {
        LoginUser current = AuthContext.require();
        if (Objects.equals(target.getId(), current.getUserId())) {
            throw new BizException(ResultCode.CANNOT_OPERATE_SELF, "不能对当前登录的账号执行「" + action + "」操作");
        }
        if (target.getRole() == RoleEnum.ADMIN) {
            throw new BizException(ResultCode.CANNOT_OPERATE_ADMIN, "不能对管理员账号执行「" + action + "」操作");
        }
    }
}
