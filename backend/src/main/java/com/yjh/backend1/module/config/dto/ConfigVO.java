package com.yjh.backend1.module.config.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 配置项（管理员视角）。
 * <p>
 * {@code masked=true} 时 {@code configValue} 是掩码 {@code ******}，不是真实值。
 * 前端提交时把掩码原样传回即表示"不修改这一项"。
 */
@Data
public class ConfigVO {

    private Long id;

    /** 配置键，如 site.name */
    private String configKey;

    /** STRING / TEXT / NUMBER / BOOLEAN / PASSWORD */
    private String configType;

    /** SITE / SECURITY / OTHER */
    private String groupName;

    /** 中文说明 */
    private String description;

    /** 值；PASSWORD 类型为掩码 */
    private String configValue;

    /** 值是否被打码（PASSWORD 类型） */
    private Boolean masked;

    /** 是否公开（未登录可读） */
    private Boolean publicVisible;

    /** 是否系统内置（可改不可删） */
    private Boolean builtin;

    private LocalDateTime updateTime;
}
