package com.yjh.backend1.module.config.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 保存单个配置项。
 * <p>
 * 批量保存时只用到 {@code configKey} + {@code configValue}；
 * 新增时还会用到类型/分组/说明/是否公开。
 */
@Data
public class ConfigSaveRequest {

    @NotBlank(message = "配置键不能为空")
    @Size(max = 64, message = "配置键不能超过 64 字符")
    private String configKey;

    @Size(max = 2000, message = "配置值不能超过 2000 字符")
    private String configValue;

    /** STRING / TEXT / NUMBER / BOOLEAN / PASSWORD，新增时必填，批量保存时忽略 */
    private String configType;

    /** SITE / SECURITY / OTHER，新增时可选，默认 OTHER */
    private String groupName;

    @Size(max = 255, message = "说明不能超过 255 字符")
    private String description;

    /** 是否公开，新增时使用 */
    private Boolean publicVisible;
}
