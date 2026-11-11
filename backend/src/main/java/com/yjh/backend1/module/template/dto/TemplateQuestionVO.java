package com.yjh.backend1.module.template.dto;

import lombok.Data;

import java.util.List;

/**
 * 模板结构快照里的单道题。
 * <p>
 * 与 {@link com.yjh.backend1.module.survey.dto.QuestionVO} 的关键区别：
 * <b>选项是文本数组，不是带 ID 的对象</b>。模板必须与来源问卷彻底解耦，
 * 存选项 ID 会在来源问卷被修改后让模板指向不存在的选项。
 */
@Data
public class TemplateQuestionVO {

    private String questionType;
    private String title;
    private String description;
    /** 是否必答：1=是 0=否 */
    private Integer isRequired;
    private Integer maxLength;
    private Integer ratingMax;
    private Integer sortNo;
    /** 选项文本；非选择题为空数组，不是 null */
    private List<String> options;
}
