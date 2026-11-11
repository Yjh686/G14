package com.yjh.backend1.module.survey.dto;

import lombok.Data;

import java.util.List;

/**
 * 题目展示对象（含选项）。
 */
@Data
public class QuestionVO {

    private Long id;
    /** 题型 */
    private String questionType;
    private String title;
    private String description;
    /** 是否必答：1=是 0=否 */
    private Integer isRequired;
    private Integer maxLength;
    private Integer ratingMax;
    private Integer sortNo;
    /** 选项列表；文本/评分题为空数组，不是 null */
    private List<OptionVO> options;
}
