package com.g14.g14.login.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 登录 / 改密成功响应。
 *
 * <p>登录与改密共用同一结构：两者都会签发新令牌。
 * 改密后旧令牌因 {@code tokenVersion+1} 立即失效，因此必须在响应里回签新令牌，
 * 否则前端保存的旧令牌下一个请求就会被拒（401/40103）。
 *
 * <p>{@code mustChangePassword=true} 时前端路由守卫会强制跳转到「修改初始密码」页。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LoginResponse {

    /** JWT，前端放入 Authorization: Bearer &lt;token&gt; */
    private String token;

    /** 固定 Bearer */
    private String tokenType;

    /** 有效期（秒） */
    private long expiresIn;

    /** 是否必须修改初始密码 */
    private boolean mustChangePassword;

    /** 当前用户信息 */
    private UserVO user;
}
