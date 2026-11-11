package com.yjh.backend1.module.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 自助注册请求（学生/教师）。
 * <p>
 * 注册后 {@code audit_status = 0}（待审核）、{@code status = 1}，
 * 必须由 ADMIN 审核通过后才能登录。这样即使有人乱注册，也不会污染名册。
 */
@Data
public class RegisterRequest {

    @NotBlank(message = "请输入学号/工号")
    @Size(min = 4, max = 64, message = "账号长度需为 4~64 位")
    @Pattern(regexp = "^[A-Za-z0-9_.-]+$", message = "账号只能包含字母、数字、下划线、点、短横线")
    private String username;

    /** 长度与复杂度由 PasswordPolicy 按系统配置校验（FR-CFG-02），这里只挡物理上限 */
    @NotBlank(message = "请输入密码")
    @Size(max = 64, message = "密码过长")
    private String password;

    @NotBlank(message = "请输入姓名")
    @Size(max = 64, message = "姓名过长")
    private String realName;

    /** 只能注册为学生或教师，注册管理员必须走 ADMIN 后台 */
    @NotBlank(message = "请选择身份")
    @Pattern(regexp = "^(STUDENT|TEACHER)$", message = "身份只能是 STUDENT 或 TEACHER")
    private String role;

    @Size(max = 128, message = "院系/班级过长")
    private String department;

    @Pattern(regexp = "^$|^1[3-9]\\d{9}$", message = "手机号格式不正确")
    private String phone;
}
