package com.yjh.backend1.security;

import com.yjh.backend1.common.BizException;
import com.yjh.backend1.common.Result;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * 认证 + 鉴权拦截器。
 * <p>
 * 执行顺序：
 * <ol>
 *   <li>白名单直接放行（登录、注册、健康检查）</li>
 *   <li>解析 Authorization: Bearer &lt;token&gt;，失败抛 401</li>
 *   <li>校验 token_version 与数据库一致（改密/禁用后旧令牌立即失效）</li>
 *   <li>写入 UserContext</li>
 *   <li>强制改密拦截：must_change_password=1 时只允许改密/登出/查自己资料</li>
 *   <li>@RequirePerm 鉴权</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuthInterceptor implements HandlerInterceptor {

    private final JwtUtil jwtUtil;
    private final AuthUserLoader authUserLoader;
    private final ObjectMapper objectMapper;

    private static final AntPathMatcher MATCHER = new AntPathMatcher();

    /**
     * 完全公开的路径（无需令牌）。
     * <p>
     * {@code /api/config/public} 必须在这里：登录页要在用户还没有令牌时渲染
     * 站点名、公告和密码规则文案。它自身只返回 is_public=1 的配置项，
     * 「公开这个路径」不等于「公开全部配置」——默认密码仍是 is_public=0。
     */
    private static final List<String> WHITE_LIST = Arrays.asList(
            "/api/auth/login",
            "/api/auth/register",
            "/api/auth/captcha",
            "/api/config/public",
            "/api/health",
            "/error"
    );

    /** 强制改密状态下仍然允许访问的路径 */
    private static final List<String> PASSWORD_CHANGE_ALLOWED = Arrays.asList(
            "/api/auth/password",
            "/api/auth/logout",
            "/api/auth/me",
            "/api/auth/password-status"
    );

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 预检请求直接放行
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        // 非 Controller 方法（静态资源、错误页）放行
        if (!(handler instanceof HandlerMethod hm)) {
            return true;
        }

        String path = request.getRequestURI();

        // 1. 白名单
        if (isWhite(path)) {
            return true;
        }

        // 2. 取令牌
        String token = resolveToken(request);
        if (token == null) {
            throw BizException.unauthorized("未登录或登录已过期");
        }

        // 3. 解析并加载用户（含版本校验、状态校验）
        LoginUser user = authUserLoader.loadFromToken(token);

        // 4. 绑定上下文
        UserContext.set(user);

        // 5. 强制改密拦截
        if (user.isMustChangePassword() && !PASSWORD_CHANGE_ALLOWED.contains(path)) {
            throw BizException.forbidden("首次登录或密码已被重置，请先修改密码");
        }

        // 6. 权限校验
        RequirePerm rp = hm.getMethodAnnotation(RequirePerm.class);
        if (rp == null) {
            rp = hm.getBeanType().getAnnotation(RequirePerm.class);
        }
        if (rp != null) {
            checkPermission(user, rp);
        }

        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        UserContext.clear();
    }

    private void checkPermission(LoginUser user, RequirePerm rp) {
        // 角色限制
        String[] roles = rp.roles();
        if (roles.length > 0) {
            boolean roleOk = Arrays.asList(roles).contains(user.getRoleCode());
            if (!roleOk) {
                throw BizException.forbidden("当前角色无权访问该接口");
            }
        }

        // 权限码：value ∪ or，任意满足其一即可
        String[] codes = concat(rp.value(), rp.or());
        if (codes.length == 0) {
            return;
        }
        Set<String> owned = user.getPermissions();
        if (owned == null) {
            throw BizException.forbidden("当前账号未配置任何权限");
        }
        for (String c : codes) {
            if (owned.contains(c)) {
                return;
            }
        }
        log.warn("权限不足 userId={} role={} need={}", user.getUserId(), user.getRoleCode(), Arrays.toString(codes));
        throw BizException.forbidden("无权限执行该操作");
    }

    private static String[] concat(String[] a, String[] b) {
        String[] r = new String[a.length + b.length];
        System.arraycopy(a, 0, r, 0, a.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }

    private boolean isWhite(String path) {
        for (String p : WHITE_LIST) {
            if (MATCHER.match(p, path)) {
                return true;
            }
        }
        return false;
    }

    private String resolveToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            String t = header.substring(7).trim();
            return t.isEmpty() ? null : t;
        }
        // 兼容前端把令牌放 query（下载导出场景）
        String t = request.getParameter("token");
        return (t == null || t.isBlank()) ? null : t;
    }

    /** 供拦截器外部（如 Spring Security 未引入时）写 JSON 错误响应 */
    public void writeJson(HttpServletResponse resp, int code, String msg) {
        try {
            resp.setStatus(code);
            resp.setCharacterEncoding(StandardCharsets.UTF_8.name());
            resp.setContentType("application/json;charset=UTF-8");
            resp.getWriter().write(objectMapper.writeValueAsString(Result.fail(code, msg)));
        } catch (Exception ignored) {
            // 响应已提交，无法再处理
        }
    }
}
