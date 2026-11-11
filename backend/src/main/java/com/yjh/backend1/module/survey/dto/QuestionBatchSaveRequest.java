package com.yjh.backend1.module.survey.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 整卷保存题目的请求体。
 * <p>
 * 特意用对象包一层，而不是直接收 {@code List<QuestionSaveRequest>}：
 * Spring 对「请求体是 List」的元素级 {@code @Valid} 不会级联，
 * 包一层后 {@code @Valid} 加在字段上才能真正触发逐题校验。
 * 这样题干为空这类错误由校验框架返回 400，而不是在业务代码里 NPE。
 */
@Data
public class QuestionBatchSaveRequest {

    @NotNull(message = "题目列表不能为 null（无题目请传空数组）")
    @Size(max = 100, message = "单份问卷最多 100 道题")
    @Valid
    private List<QuestionSaveRequest> questions;
}
