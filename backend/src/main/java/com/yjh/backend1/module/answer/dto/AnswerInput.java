package com.yjh.backend1.module.answer.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 单题作答输入。
 * <p>
 * 三种题型共用这一个结构，按题型取对应字段：选择类看 {@code optionValues}，
 * 文本类看 {@code textValue}，评分看 {@code numberValue}。
 * 用三个字段而不是「一个 Object 字段」的理由：类型明确，序列化后前端直接用，
 * 且服务端校验能针对每种题型给出准确提示。
 */
@Data
public class AnswerInput {

    @NotNull(message = "题目 ID 不能为空")
    private Long questionId;

    /** 选项 ID 数组：单选传 1 个，多选传多个；非选择题为 null */
    private List<Long> optionValues;

    /** 文本答案；非文本题为 null */
    @Size(max = 2000, message = "文本答案不能超过 2000 字")
    private String textValue;

    /** 评分值；非评分题为 null */
    private Integer numberValue;
}
