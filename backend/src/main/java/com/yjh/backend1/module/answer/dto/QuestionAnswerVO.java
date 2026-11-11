package com.yjh.backend1.module.answer.dto;

import lombok.Data;

import java.util.List;

/**
 * 单题作答（答卷详情里的一行）。
 * <p>
 * 同时给出「原值」和「可读值」：{@code optionValues} 是选项 ID，
 * {@code optionTexts} 是选项文本。前端做详情展示只要后者，
 * 但做「编辑草稿」时需要前者，两个都给省一次映射。
 */
@Data
public class QuestionAnswerVO {

    private Long questionId;
    private String questionType;
    private String questionTitle;

    /** 选项 ID 数组（选择类题） */
    private List<Long> optionValues;
    /** 选项文本数组，顺序与 optionValues 一致 */
    private List<String> optionTexts;

    private String textValue;
    private Integer numberValue;

    /** 该题是否已作答（前端可据此把「未答」显示成灰色「未作答」而不是空白） */
    private Boolean answered;
}
