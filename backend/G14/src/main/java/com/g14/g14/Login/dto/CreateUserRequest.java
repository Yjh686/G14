package com.g14.g14.login.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Pattern;
import javax.validation.constraints.Size;

/**
 * 管理员开通账号请求。
 *
 * <p>注意：前端 {@code src/api/auth.js} 会<b>显式传 null</b> 表示「不填」，
 * 例如 {@code phone: null}、{@code initialPassword: null}。
 * 因此这里的约束必须允许 null（Bean Validation 的 {@code @Pattern}/{@code @Size}
 * 对 null 值默认放行），否则前端会被 400 拒绝。
 */
@Data
public class CreateUserRequest {

    /** 学生填学号，老师填工号 */
    @NotBlank(message = "账号不能为空")
    @Size(min = 4, max = 64, message = "账号长度必须为 4~64 位")
    @Pattern(regexp = "^[A-Za-z0-9_-]+$", message = "账号只能包含字母、数字、下划线和短横线")
    private String account;

    @NotBlank(message = "姓名不能为空")
    @Size(max = 64, message = "姓名长度不能超过 64")
    private String realName;

    /** STUDENT / TEACHER / ADMIN */
    @NotBlank(message = "角色不能为空")
    private String role;

    /** 手机号，可为 null；填写后老师可凭手机号登录 */
    @Pattern(regexp = "^$|^1[3-9]\\d{9}$", message = "手机号格式不正确")
    private String phone;

    /**
     * 初始密码，可为 null。
     * 为 null 时后端使用配置项 {@code app.auth.initial-password}。
     */
    @Size(max = 64, message = "初始密码长度不能超过 64")
    private String initialPassword;
}
