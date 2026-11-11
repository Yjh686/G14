package com.yjh.backend1.module.auth.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.Set;

/**
 * 登录成功响应。
 * <p>
 * {@code permissions} 返回给前端只用于「按权限显示菜单/按钮」，
 * <b>不是安全边界</b>，真正的校验永远在后端拦截器。
 */
@Data
@AllArgsConstructor
public class LoginResponse {

    private String token;
    /** 令牌有效期（秒） */
    private long expiresIn;
    private Long userId;
    private String username;
    private String realName;
    private String roleCode;
    private String roleName;
    private boolean mustChangePassword;
    private Set<String> permissions;
}
