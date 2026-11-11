package com.yjh.backend1.module.template.dto;

import lombok.Data;

import java.util.List;

/**
 * {@code survey_template.structure_json} 的 Java 形态。
 * <p>
 * 用对象包一层 {@code questions}，和问卷模块的 {@code QuestionBatchSaveRequest} 保持同一形状：
 * 前端在两个模块之间搬运题目结构时可以直接复用同一段序列化代码；
 * 将来要往快照里加字段（例如「预计填写时长」）也不必改动外层结构。
 */
@Data
public class TemplateStructure {

    private List<TemplateQuestionVO> questions;
}
