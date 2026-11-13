package com.yjh.backend1.module.recovery.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 找回密码第二步：答题并重设密码。
 * <p>
 * 两步之间不发放「临时票据」：因为密保问题本身是公开的（第一步任何人只要知道
 * 学号就能看到），临时票据能保护的信息量约等于零，却会多一份需要存储、
 * 需要过期、需要防止重放的令牌。直接把两步合成一次提交，逻辑更短，
 * 一旦答案正确就立刻校验新密码规则并落库。
 */
@Data
public class RecoveryResetRequest {

    @NotBlank(message = "请输入学号/工号")
    @Size(max = 64, message = "账号过长")
    private String username;

    @NotBlank(message = "请回答密保问题")
    @Size(max = 200, message = "答案过长")
    private String answer1;

    @NotBlank(message = "请回答密保问题")
    @Size(max = 200, message = "答案过长")
    private String answer2;

    /** 长度与复杂度由 PasswordPolicy 按系统配置校验（FR-CFG-02） */
    @NotBlank(message = "请输入新密码")
    @Size(max = 64, message = "密码过长")
    private String newPassword;
}
