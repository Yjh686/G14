package com.g14.g14.login.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

/**
 * 修改密码请求（用户自助 / 首次登录强制改密复用同一接口）。
 * <p>前端只发 {@code oldPassword} 与 {@code newPassword}。
 */
@Data
public class ChangePasswordRequest {

    @NotBlank(message = "原密码不能为空")
    @Size(max = 64, message = "原密码长度不能超过 64")
    private String oldPassword;

    @NotBlank(message = "新密码不能为空")
    @Size(max = 32, message = "新密码长度不能超过 32")
    private String newPassword;
}
