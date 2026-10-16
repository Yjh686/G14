package com.g14.g14.login.entity;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 登录审计日志，对应表 sys_login_log。
 */
@Data
public class LoginLog implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;
    private Long userId;
    private String account;
    /** ACCOUNT / PHONE */
    private String loginType;
    /** 1=成功，0=失败 */
    private Integer result;
    private String reason;
    private String ip;
    private String userAgent;
    private LocalDateTime createdAt;
}
