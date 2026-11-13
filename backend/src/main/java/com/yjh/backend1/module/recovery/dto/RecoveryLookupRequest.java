package com.yjh.backend1.module.recovery.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 找回密码第一步：用账号换密保问题。
 */
@Data
public class RecoveryLookupRequest {

    @NotBlank(message = "请输入学号/工号")
    @Size(max = 64, message = "账号过长")
    private String username;
}
