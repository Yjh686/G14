package com.yjh.backend1.module.survey.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 选项保存入参。
 */
@Data
public class OptionSaveRequest {

    /** 选项文本 */
    @NotBlank(message = "选项内容不能为空")
    @Size(max = 255, message = "选项内容不能超过 255 字")
    private String content;
}
