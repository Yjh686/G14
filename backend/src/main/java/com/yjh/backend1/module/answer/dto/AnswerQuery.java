package com.yjh.backend1.module.answer.dto;

import lombok.Data;

/**
 * 答卷列表查询条件。
 */
@Data
public class AnswerQuery {

    /** 按问卷筛选（必填，答卷总是挂在某份问卷下） */
    private Long surveyId;

    /** 有效性：null=全部；1=仅有效；0=仅无效 */
    private Integer isValid;

    /** 排序：submit_desc（默认，最新在前）/ submit_asc */
    private String order;

    private Integer page;
    private Integer size;
}
