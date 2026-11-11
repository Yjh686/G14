package com.yjh.backend1.security;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Set;

/**
 * 登录用户快照：请求级身份信息。
 * <p>
 * 由 {@code AuthInterceptor} 从 JWT 解析出 userId 后，再查库/缓存填充，
 * 放进 {@link UserContext}。业务代码通过 {@code UserContext.get()} 取用。
 * <p>
 * 特别注意：这里 <b>不含密码</b>，也 <b>不含任何答卷身份关联</b>，
 * 匿名问卷的场景下身份只用于「限填一次」校验，绝不回传给发布者。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LoginUser {

    private Long userId;
    private String username;
    private String realName;
    /** 角色码：ROLE_ADMIN / ROLE_TEACHER / ROLE_STUDENT */
    private String roleCode;
    /** 角色显示名 */
    private String roleName;
    /** 是否强制改密：该值为 1 时，除改密/登出/查自己资料外的接口都返回 403 */
    private boolean mustChangePassword;
    /** 生效的权限码集合（角色权限 ∪ 用户追加权限） */
    private Set<String> permissions;

    public boolean isAdmin() {
        return "ROLE_ADMIN".equals(roleCode);
    }

    public boolean hasPermission(String code) {
        return permissions != null && permissions.contains(code);
    }
}
