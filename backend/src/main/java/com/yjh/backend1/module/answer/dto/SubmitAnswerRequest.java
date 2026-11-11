package com.yjh.backend1.module.answer.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 提交答卷请求。
 * <p>
 * 用对象包一层 {@code answers}，而不是让请求体直接是数组：
 * Spring 对「请求体直接是 List」的元素级 {@code @Valid} 不级联，
 * 包一层之后 {@link Valid} 才能生效（问卷模块的 questions 已经踩过这个坑）。
 */
@Data
public class SubmitAnswerRequest {

    @NotNull(message = "作答列表不能为 null（无作答请传空数组）")
    @Size(max = 100, message = "单份答卷的作答数不能超过 100")
    @Valid
    private List<AnswerInput> answers;
}
