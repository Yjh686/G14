package com.yjh.backend1.module.log.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 操作日志行。
 * <p>
 * 直接暴露 {@code userId} 是刻意的：操作日志是审计用途，管理员需要知道「是谁做了什么」。
 * 与之相对，答卷相关的匿名约束不适用于这里 —— 匿名问卷提交根本不写操作日志。
 */
@Data
public class OperationLogVO {

    private Long id;
    private Long userId;
    private String username;
    /** 模块，如「问卷管理」「用户管理」「数据导出」 */
    private String module;
    /** 动作，如「发布问卷」「重置密码」 */
    private String action;
    /** 目标类型，如 survey / sys_user / answer_sheet */
    private String targetType;
    private Long targetId;
    private String detail;
    /** 1=成功 0=失败 */
    private Integer result;
    private String ip;
    private LocalDateTime createTime;
}
