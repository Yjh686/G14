package com.yjh.backend1.module.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 登录请求。
 * <p>
 * 登录失败一律返回「账号或密码错误」，不区分"账号不存在"和"密码错误"，
 * 避免账号枚举。真实原因只写进 sys_login_log 供管理员排查。
 */
@Data
public class LoginRequest {

    @NotBlank(message = "请输入账号")
    @Size(max = 64, message = "账号过长")
    private String username;

    @NotBlank(message = "请输入密码")
    @Size(max = 64, message = "密码过长")
    private String password;
}
