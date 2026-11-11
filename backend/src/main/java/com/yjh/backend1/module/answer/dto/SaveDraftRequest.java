package com.yjh.backend1.module.answer.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 保存草稿请求。
 * <p>
 * 与提交的区别：草稿允许缺题、允许超长文本被截断，不需要「必答校验」。
 * 但结构仍校验（questionId 非空、列表不超过 100），否则脏数据会一直堆在 JSON 里。
 */
@Data
public class SaveDraftRequest {

    @NotNull(message = "作答列表不能为 null（清空草稿请传空数组）")
    @Size(max = 100, message = "草稿的作答数不能超过 100")
    @Valid
    private List<AnswerInput> answers;
}
