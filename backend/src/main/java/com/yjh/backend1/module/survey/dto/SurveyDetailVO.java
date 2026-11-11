package com.yjh.backend1.module.survey.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

/**
 * 问卷详情（列表项 + 题目列表）。
 * <p>
 * 编辑页、预览页、填写页共用这一个结构：
 * 编辑页需要题目结构，填写页也需要题目结构，字段完全一致，
 * 分成三个 DTO 只会带来重复维护成本。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class SurveyDetailVO extends SurveyVO {

    /** 题目列表，按 sort_no 升序；无题目时为空数组 */
    private List<QuestionVO> questions;
}
