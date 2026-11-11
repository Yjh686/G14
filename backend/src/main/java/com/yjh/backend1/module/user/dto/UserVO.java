package com.yjh.backend1.module.user.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户列表/详情视图。
 * <p>
 * <b>绝不含 passwordHash、tokenVersion</b>。
 * passwordHash 泄露等于把离线爆破的把手递给别人；
 * tokenVersion 属于安全实现细节，前端不需要也不该知道。
 */
@Data
public class UserVO {

    private Long id;
    private String username;
    private String realName;
    private Integer status;
    private Integer auditStatus;
    private String auditRemark;
    private Integer mustChangePassword;
    private String phone;
    private String email;
    private String department;
    private String remark;
    private String roleCode;
    private String roleName;
    private LocalDateTime lastLoginTime;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
