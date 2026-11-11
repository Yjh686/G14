package com.yjh.backend1.module.survey.dto;

import lombok.Data;

/**
 * 问卷列表查询条件。
 */
@Data
public class SurveyQuery {

    /** 关键词：匹配标题 */
    private String keyword;

    /** 状态：DRAFT / PUBLISHED / PAUSED / CLOSED，null=全部 */
    private String status;

    /** 分类：TEACHING / ACTIVITY / SATISFACTION / OTHER，null=全部 */
    private String category;

    /**
     * 范围：mine=仅自己创建（默认）；all=全量（仅 ADMIN 用，需 survey:list:all）。
     * 不靠前端传参决定权限，服务层会二次校验。
     */
    private String scope;

    private Integer page;
    private Integer size;
}
