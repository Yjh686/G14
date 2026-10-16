package com.g14.g14.login.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.g14.g14.login.common.PhoneMask;
import com.g14.g14.login.entity.User;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 对外暴露的用户视图对象。
 *
 * <p>三条硬约束（均由前端契约测试断言）：
 * <ol>
 *   <li>字段名是 {@code realName}（前端 {@code utils/user.js} 的 normalizeUser 依赖它）；</li>
 *   <li>{@code phone} 必须脱敏为 {@code 138****0001}；</li>
 *   <li>绝不能出现 {@code passwordHash}、{@code tokenVersion}、{@code loginFailCount} 等内部字段。</li>
 * </ol>
 */
@Data
public class UserVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;
    private String account;
    private String realName;
    /** STUDENT / TEACHER / ADMIN */
    private String role;
    /** 角色中文名，供界面直接展示 */
    private String roleLabel;
    /** 1=正常 0=禁用 */
    private Integer status;
    /** 已脱敏的手机号 */
    private String phone;
    private Boolean mustChangePassword;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime lastLoginTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;

    public static UserVO from(User user) {
        if (user == null) {
            return null;
        }
        UserVO vo = new UserVO();
        vo.id = user.getId();
        vo.account = user.getAccount();
        vo.realName = user.getRealName();
        vo.role = user.getRole() == null ? null : user.getRole().name();
        vo.roleLabel = user.getRole() == null ? null : user.getRole().getLabel();
        vo.status = user.getStatus();
        vo.phone = PhoneMask.mask(user.getPhone());
        vo.mustChangePassword = user.requiresPasswordChange();
        vo.lastLoginTime = user.getLastLoginTime();
        vo.createdAt = user.getCreatedAt();
        return vo;
    }
}
