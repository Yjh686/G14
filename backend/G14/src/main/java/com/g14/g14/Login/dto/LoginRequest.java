package com.g14.g14.login.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

/**
 * 登录请求。学生传学号，老师传工号或手机号，管理员传管理员账号。
 * <p>前端不提交角色 —— 角色一律由后端查库识别，防止前端伪造。
 */
@Data
public class LoginRequest {

    @NotBlank(message = "账号不能为空")
    @Size(max = 64, message = "账号长度不能超过 64")
    private String account;

    @NotBlank(message = "密码不能为空")
    @Size(max = 64, message = "密码长度不能超过 64")
    private String password;
}
