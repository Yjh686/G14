package com.g14.g14.login.security;

import com.g14.g14.login.common.BizException;
import com.g14.g14.login.common.ResultCode;
import com.g14.g14.login.entity.User;
import com.g14.g14.login.enums.RoleEnum;
import com.g14.g14.login.mapper.UserMapper;
import io.jsonwebtoken.Claims;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.StringUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * ===================== 接口鉴权中间件（核心） =====================
 *
 * <p>执行链路（任一环节失败立即中断并返回统一错误体）：
 * <ol>
 *   <li>非 Controller 请求（静态资源、错误页）与 CORS 预检请求直接放行；</li>
 *   <li>{@link PublicApi} 标注的匿名接口放行；</li>
 *   <li>提取 {@code Authorization: Bearer <token>}，缺失 → 401/40101；</li>
 *   <li>校验签名与有效期，失败 → 401/40102（过期 401/40100）；</li>
 *   <li>校验登出黑名单 → 401/40103；</li>
 *   <li>按 Token 中的用户 ID <b>实时查库</b>，依次校验：
 *       用户存在 → 账号未被禁用（403/40301）→ tokenVersion 一致（401/40103）。
 *       这一步保证「管理员禁用账号」与「重置密码」后，旧令牌立即失效；</li>
 *   <li>强制改密拦截：处于必须改密状态时只放行白名单接口，其余 → 403/40302；</li>
 *   <li>角色鉴权：解析 {@link RequireRole}，不匹配 → 403/40300；</li>
 *   <li>写入 {@link AuthContext}，供后续业务代码读取当前用户。</li>
 * </ol>
 *
 * <p><b>设计取舍</b>：第 6 步使鉴权具备实时性，代价是每请求一次主键查询。
 * 若 QPS 上升，可把用户的状态 / 角色 / tokenVersion 缓存到 Redis（TTL 30~60s），
 * 并接受秒级的失效延迟。
 *
 * <p><b>分层边界</b>：本中间件只解决「是否登录 + 角色是否允许」。
 * 「资源属主 / 数据范围」属于业务层校验，见 {@code PermissionService}。
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(AuthInterceptor.class);

    /**
     * 强制改密状态下仍可访问的接口。
     * 必须保留：查自己信息、改密码、登出；否则用户会陷入死循环、无法完成改密。
     */
    private static final List<String> FORCE_PWD_CHANGE_WHITELIST = Arrays.asList(
            "/api/auth/me",
            "/api/auth/password",
            "/api/auth/logout"
    );

    private final JwtUtil jwtUtil;
    private final TokenBlacklist tokenBlacklist;
    private final UserMapper userMapper;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    public AuthInterceptor(JwtUtil jwtUtil, TokenBlacklist tokenBlacklist, UserMapper userMapper) {
        this.jwtUtil = jwtUtil;
        this.tokenBlacklist = tokenBlacklist;
        this.userMapper = userMapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // 1. 静态资源等非 Controller 请求
        if (!(handler instanceof HandlerMethod)) {
            return true;
        }
        // 2. CORS 预检请求不带 Authorization 头，必须放行
        if (HttpMethod.OPTIONS.matches(request.getMethod())) {
            return true;
        }

        HandlerMethod handlerMethod = (HandlerMethod) handler;

        // 3. 匿名接口（@PublicApi）
        if (isPublicApi(handlerMethod)) {
            return true;
        }

        // 4. 提取并校验 Token
        String token = resolveToken(request);
        if (!StringUtils.hasText(token)) {
            throw new BizException(ResultCode.TOKEN_MISSING);
        }
        Claims claims = jwtUtil.parse(token);

        // 5. 登出黑名单
        String jti = claims.getId();
        if (tokenBlacklist.contains(jti)) {
            throw new BizException(ResultCode.TOKEN_REVOKED);
        }

        // 6. 实时查库：账号存在 → 未被禁用 → 令牌版本一致
        Long userId = jwtUtil.getUserId(claims);
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BizException(ResultCode.TOKEN_REVOKED, "用户不存在，请重新登录");
        }
        if (!user.enabled()) {
            // 注意：这里是 403 而不是 401 —— 前端 request.js 按 403/40301 走「提示 + 清登录态回登录页」
            throw new BizException(ResultCode.ACCOUNT_DISABLED);
        }
        if (!Objects.equals(user.getTokenVersion(), jwtUtil.getTokenVersion(claims))) {
            throw new BizException(ResultCode.TOKEN_REVOKED);
        }

        // 7. 强制改密拦截
        if (user.requiresPasswordChange() && !inForceChangeWhitelist(request)) {
            throw new BizException(ResultCode.MUST_CHANGE_PASSWORD);
        }

        // 8. 角色鉴权
        RequireRole requireRole = resolveRequireRole(handlerMethod);
        if (requireRole != null && !matchRole(requireRole, user.getRole())) {
            log.warn("越权访问被拦截: userId={}, role={}, uri={}",
                    userId, user.getRole(), request.getRequestURI());
            throw new BizException(ResultCode.FORBIDDEN);
        }

        // 9. 绑定请求级上下文
        AuthContext.set(LoginUser.of(user, token, jti, jwtUtil.getExpireAtMillis(claims)));
        return true;
    }

    /**
     * 请求结束务必清理 ThreadLocal，避免 Tomcat 线程复用导致用户身份串号。
     */
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        AuthContext.clear();
    }

    /* ------------------------------------------------------------------ */

    private boolean isPublicApi(HandlerMethod handlerMethod) {
        return handlerMethod.hasMethodAnnotation(PublicApi.class)
                || handlerMethod.getBeanType().isAnnotationPresent(PublicApi.class);
    }

    /**
     * 从请求头提取 Token。同时兼容 {@code Authorization: <token>}（无 Bearer 前缀）的写法。
     */
    private String resolveToken(HttpServletRequest request) {
        String header = request.getHeader(JwtUtil.HEADER_NAME);
        if (!StringUtils.hasText(header)) {
            return null;
        }
        String value = header.trim();
        if (value.regionMatches(true, 0, JwtUtil.TOKEN_PREFIX, 0, JwtUtil.TOKEN_PREFIX.length())) {
            return value.substring(JwtUtil.TOKEN_PREFIX.length()).trim();
        }
        return value;
    }

    private boolean inForceChangeWhitelist(HttpServletRequest request) {
        String path = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (StringUtils.hasText(contextPath) && path.startsWith(contextPath)) {
            path = path.substring(contextPath.length());
        }
        for (String pattern : FORCE_PWD_CHANGE_WHITELIST) {
            if (pathMatcher.match(pattern, path)) {
                return true;
            }
        }
        return false;
    }

    /** 方法级注解优先于类级注解。 */
    private RequireRole resolveRequireRole(HandlerMethod handlerMethod) {
        RequireRole onMethod = handlerMethod.getMethodAnnotation(RequireRole.class);
        if (onMethod != null) {
            return onMethod;
        }
        return handlerMethod.getBeanType().getAnnotation(RequireRole.class);
    }

    private boolean matchRole(RequireRole requireRole, RoleEnum actualRole) {
        if (actualRole == null) {
            return false;
        }
        // 需求：管理员拥有全部权限
        if (requireRole.allowAdminBypass() && actualRole == RoleEnum.ADMIN) {
            return true;
        }
        for (RoleEnum allowed : requireRole.value()) {
            if (allowed == actualRole) {
                return true;
            }
        }
        return false;
    }
}
