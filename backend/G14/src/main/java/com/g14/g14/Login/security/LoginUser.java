package com.g14.g14.login.security;

import com.g14.g14.login.entity.User;
import com.g14.g14.login.enums.RoleEnum;
import lombok.Data;

import java.io.Serializable;

/**
 * 当前登录用户（请求级主体），由 {@link AuthInterceptor} 解析 Token 后写入 {@link AuthContext}。
 */
@Data
public class LoginUser implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long userId;
    private String account;
    private String realName;
    private RoleEnum role;
    /** 是否处于「必须修改密码」状态 */
    private boolean mustChangePassword;
    /** 本次请求携带的原始 Token（登出时需要） */
    private String token;
    /** Token 唯一 ID（登出时写入黑名单） */
    private String jti;
    /** Token 到期时间戳（毫秒） */
    private long tokenExpireAt;

    public static LoginUser of(User user, String token, String jti, long tokenExpireAt) {
        LoginUser lu = new LoginUser();
        lu.userId = user.getId();
        lu.account = user.getAccount();
        lu.realName = user.getRealName();
        lu.role = user.getRole();
        lu.mustChangePassword = user.requiresPasswordChange();
        lu.token = token;
        lu.jti = jti;
        lu.tokenExpireAt = tokenExpireAt;
        return lu;
    }

    public boolean isAdmin() {
        return role == RoleEnum.ADMIN;
    }

    public boolean isTeacher() {
        return role == RoleEnum.TEACHER;
    }

    public boolean isStudent() {
        return role == RoleEnum.STUDENT;
    }
}
