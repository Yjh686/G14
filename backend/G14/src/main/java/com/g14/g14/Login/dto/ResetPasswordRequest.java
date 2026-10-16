package com.g14.g14.login.dto;

import lombok.Data;

import javax.validation.constraints.Size;

/**
 * 管理员重置密码请求。
 *
 * <p>{@code newPassword} 为 null 时走「恢复系统初始密码」分支：
 * 使用配置项 {@code app.auth.initial-password}，且只做长度校验。
 * 这是必须保留的能力 —— 系统初始密码本身就在弱口令黑名单里，
 * 若对它套用完整强度策略会自相矛盾（前端 tools/demo-fixture.mjs 依赖该分支复位演示账号）。
 */
@Data
public class ResetPasswordRequest {

    /** 新密码，可为 null（null = 恢复系统初始密码） */
    @Size(max = 64, message = "密码长度不能超过 64")
    private String newPassword;
}
