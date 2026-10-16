package com.g14.g14.login.security;

import com.g14.g14.login.common.BizException;
import com.g14.g14.login.common.ResultCode;
import com.g14.g14.login.enums.RoleEnum;

/**
 * 请求级用户上下文（ThreadLocal）。
 *
 * <p>生命周期与请求绑定：{@code AuthInterceptor#preHandle} 写入，
 * {@code afterCompletion} 清理。务必成对出现，否则线程池复用会造成用户串号。
 */
public final class AuthContext {

    private static final ThreadLocal<LoginUser> HOLDER = new ThreadLocal<>();

    private AuthContext() {
    }

    public static void set(LoginUser user) {
        HOLDER.set(user);
    }

    /** 获取当前用户，可能为 null（匿名接口）。 */
    public static LoginUser get() {
        return HOLDER.get();
    }

    /** 获取当前用户，未登录直接抛 401。 */
    public static LoginUser require() {
        LoginUser user = HOLDER.get();
        if (user == null) {
            throw new BizException(ResultCode.UNAUTHORIZED);
        }
        return user;
    }

    public static Long currentUserId() {
        return require().getUserId();
    }

    public static RoleEnum currentRole() {
        return require().getRole();
    }

    public static boolean isAdmin() {
        LoginUser user = HOLDER.get();
        return user != null && user.isAdmin();
    }

    public static void clear() {
        HOLDER.remove();
    }
}
