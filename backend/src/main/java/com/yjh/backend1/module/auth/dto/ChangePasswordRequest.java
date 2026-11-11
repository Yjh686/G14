package com.yjh.backend1.module.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 修改自己的密码。
 */
@Data
public class ChangePasswordRequest {

    @NotBlank(message = "请输入原密码")
    @Size(max = 64, message = "密码过长")
    private String oldPassword;

    /** 长度与复杂度由 PasswordPolicy 按系统配置校验（FR-CFG-02） */
    @NotBlank(message = "请输入新密码")
    @Size(max = 64, message = "密码过长")
    private String newPassword;
}
