package com.yjh.backend1.module.log.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 登录日志行。
 * <p>
 * {@code userId} 可能为 null（账号不存在的失败尝试），但 {@code username} 一定记录 ——
 * 排查「有人在猜账号」靠的是 username 而不是 userId。
 */
@Data
public class LoginLogVO {

    private Long id;
    private Long userId;
    private String username;
    /** 1=成功 0=失败 */
    private Integer result;
    /** 成功时为「登录成功」，失败时为具体原因（账号或密码错误 / 账号不存在 / 已锁定…） */
    private String reason;
    private String ip;
    private String userAgent;
    private LocalDateTime createTime;
}
