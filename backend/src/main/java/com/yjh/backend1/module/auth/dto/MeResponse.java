package com.yjh.backend1.module.auth.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.Set;

/**
 * 当前登录用户资料（/api/auth/me）。
 * <p>
 * 刻意不含：密码哈希、token_version、登录失败次数。
 * 这些是实现细节，暴露出去只会给攻击者提供信息。
 */
@Data
public class MeResponse {

    private Long userId;
    private String username;
    private String realName;
    private String roleCode;
    private String roleName;
    private String department;
    private String phone;
    private String email;
    private boolean mustChangePassword;
    private LocalDateTime lastLoginTime;
    private Set<String> permissions;
}
