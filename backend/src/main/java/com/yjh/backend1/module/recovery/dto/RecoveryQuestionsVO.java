package com.yjh.backend1.module.recovery.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 找回密码第一步的响应：只回密保问题文本，不回答案相关的任何东西。
 * <p>
 * 刻意不返回 userId：下一步提交只要账号 + 两个答案 + 新密码，
 * 前端拿不到用户 ID 也就没法构造出「绕过账号、直接改某个 ID 的密码」的请求。
 * 服务端每次都从账号重新查用户，把身份判定留在后端。
 */
@Data
@AllArgsConstructor
public class RecoveryQuestionsVO {

    private String username;
    private String question1;
    private String question2;
}
