package com.yjh.backend1.module.survey.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 题目保存入参（差量保存用）。
 * <p>
 * 一次请求提交整份问卷的题目列表，服务端按「有 id 则更新、无 id 则新增、
 * 库里多出来的删除」做差量同步。
 * <p>
 * 为什么不用「每题一个接口」：拖拽排序会一次改动多题的 sort_no，
 * 逐个调用既慢（十几题 = 十几个请求）又无法保证顺序一致。
 * 整卷提交天然是原子的，一次事务内完成。
 */
@Data
public class QuestionSaveRequest {

    /**
     * 题目 ID；新增题目传 null。
     * 传了 ID 但属于别的问卷 → 400，防止越权改他人题目。
     */
    private Long id;

    /** 题型：SINGLE / MULTIPLE / TEXT / TEXTAREA / RATING */
    @NotBlank(message = "题型不能为空")
    private String questionType;

    @NotBlank(message = "题干不能为空")
    @Size(max = 255, message = "题干不能超过 255 字")
    private String title;

    @Size(max = 500, message = "题目说明不能超过 500 字")
    private String description;

    /** 是否必答：1=是 0=否，null 视为 0 */
    private Integer isRequired;

    /** 文本题最大字数（TEXT/TEXTAREA），null 表示不限，上限 2000 */
    @Min(value = 1, message = "最大字数至少为 1")
    @Max(value = 2000, message = "最大字数不能超过 2000")
    private Integer maxLength;

    /** 评分题最高分（RATING），null 视为 5 */
    @Min(value = 2, message = "评分上限至少为 2")
    @Max(value = 10, message = "评分上限不能超过 10")
    private Integer ratingMax;

    /** 选项列表（SINGLE/MULTIPLE 必填且 >= 2 项） */
    @Valid
    private List<OptionSaveRequest> options;
}
