package com.g14.g14.login.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.g14.g14.login.enums.RoleEnum;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 用户实体，对应表 sys_user。
 *
 * <p>{@code passwordHash} 标注 {@link JsonIgnore}：即使该实体被误当作响应体返回，
 * 也不会把哈希泄漏给前端（纵深防御）。正式接口一律返回 {@code UserVO}。
 */
@Data
public class User implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 主键 */
    private Long id;

    /** 登录账号：学生学号 / 老师工号 / 管理员账号 */
    private String account;

    /** bcrypt 密码哈希 */
    @JsonIgnore
    private String passwordHash;

    /** 姓名 */
    private String realName;

    /** 角色 */
    private RoleEnum role;

    /** 状态：1=正常，0=禁用 */
    private Integer status;

    /** 1=必须修改密码（首次开通或被管理员重置后置 1） */
    private Integer mustChangePassword;

    /** 手机号（明文存储，输出时脱敏） */
    private String phone;

    /** 令牌版本号：改密 / 重置 / 禁用时 +1，使该用户所有已签发令牌立即失效 */
    private Integer tokenVersion;

    /** 连续登录失败次数 */
    private Integer loginFailCount;

    /** 锁定截止时间 */
    private LocalDateTime lockedUntil;

    /** 最近一次登录成功时间 */
    private LocalDateTime lastLoginTime;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    /* ---------- 语义化辅助方法（命名避开 getter 前缀，防止 Jackson 重复序列化） ---------- */

    /** 账号是否可用。 */
    @JsonIgnore
    public boolean enabled() {
        return status != null && status == 1;
    }

    /** 是否处于防爆破锁定期。 */
    @JsonIgnore
    public boolean locked() {
        return lockedUntil != null && lockedUntil.isAfter(LocalDateTime.now());
    }

    /** 是否被强制要求修改密码。 */
    @JsonIgnore
    public boolean requiresPasswordChange() {
        return mustChangePassword != null && mustChangePassword == 1;
    }

    /** 是否允许使用手机号登录（需求：学生用学号登录，老师用工号或手机号登录）。 */
    @JsonIgnore
    public boolean phoneLoginAllowed() {
        return role == RoleEnum.TEACHER || role == RoleEnum.ADMIN;
    }
}
