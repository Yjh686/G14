package com.yjh.backend1.module.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * ADMIN 新建用户（导入或手工建）。建完即可登录，默认要求首次登录改密。
 */
@Data
public class CreateUserRequest {

    @NotBlank(message = "请输入学号/工号")
    @Size(min = 4, max = 64, message = "账号长度需为 4~64 位")
    @Pattern(regexp = "^[A-Za-z0-9_.-]+$", message = "账号只能包含字母、数字、下划线、点、短横线")
    private String username;

    @NotBlank(message = "请输入姓名")
    @Size(max = 64, message = "姓名过长")
    private String realName;

    /**
      * 未填则用系统默认密码，并要求首次登录改密。
      * 长度与复杂度由 PasswordPolicy 按系统配置校验（FR-CFG-02）。
      */
    @Size(max = 64, message = "密码过长")
    private String password;

    @NotBlank(message = "请选择角色")
    @Pattern(regexp = "^(ROLE_ADMIN|ROLE_TEACHER|ROLE_STUDENT)$", message = "角色不合法")
    private String roleCode;

    @Size(max = 128, message = "院系/班级过长")
    private String department;

    @Pattern(regexp = "^$|^1[3-9]\\d{9}$", message = "手机号格式不正确")
    private String phone;

    @Size(max = 128, message = "邮箱过长")
    private String email;

    @Size(max = 255, message = "备注过长")
    private String remark;
}
