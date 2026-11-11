package com.yjh.backend1.security;

import com.yjh.backend1.common.BizException;

/**
 * 当前请求的登录用户上下文（ThreadLocal）。
 * <p>
 * 由 {@code AuthInterceptor} 在 preHandle 写入、afterCompletion 清理。
 * 业务层不要缓存这里的对象引用到成员变量里。
 */
public final class UserContext {

    private static final ThreadLocal<LoginUser> HOLDER = new ThreadLocal<>();

    private UserContext() {
    }

    public static void set(LoginUser user) {
        HOLDER.set(user);
    }

    /** @return 可能为 null（未登录 / 白名单接口） */
    public static LoginUser get() {
        return HOLDER.get();
    }

    /** 取当前用户，未登录直接抛 401 */
    public static LoginUser require() {
        LoginUser u = HOLDER.get();
        if (u == null) {
            throw BizException.unauthorized("未登录或登录已过期");
        }
        return u;
    }

    public static Long requireUserId() {
        return require().getUserId();
    }

    /** @return 未登录返回 null，不抛异常 */
    public static Long currentUserIdOrNull() {
        LoginUser u = HOLDER.get();
        return u == null ? null : u.getUserId();
    }

    public static void clear() {
        HOLDER.remove();
    }
}
