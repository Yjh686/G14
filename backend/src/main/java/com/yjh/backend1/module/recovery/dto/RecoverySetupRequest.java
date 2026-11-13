package com.yjh.backend1.module.recovery.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 设置 / 修改自己的密保问题（需登录）。
 * <p>
 * 用对象包一层而不是请求体直接是 List：Spring 对「请求体是裸数组」的元素级
 * @Valid 不级联，两个问题里哪个字段为空就不会被拦下（问卷模块踩过）。
 * 这里用 {@code List<QuestionAnswer>} 包在对象里，同时限定必须是 2 条。
 */
@Data
public class RecoverySetupRequest {

    @Size(min = 2, max = 2, message = "必须设置 2 个密保问题")
    @Valid
    private List<QuestionAnswer> items;

    @Data
    public static class QuestionAnswer {

        @NotBlank(message = "密保问题不能为空")
        @Size(max = 120, message = "问题过长")
        private String question;

        @NotBlank(message = "密保答案不能为空")
        @Size(max = 200, message = "答案过长")
        private String answer;
    }
}
