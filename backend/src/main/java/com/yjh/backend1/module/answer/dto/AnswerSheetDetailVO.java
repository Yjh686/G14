package com.yjh.backend1.module.answer.dto;

import com.yjh.backend1.module.survey.dto.QuestionVO;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

/**
 * 答卷详情：答卷头 + 逐题作答。
 * <p>
 * 匿名问卷下 {@code sheet.userId/fillerName/fillerUsername} 为 null（由父类保证），
 * 且题干只带题目标题，不带任何能反推填写人的信息。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class AnswerSheetDetailVO extends AnswerSheetVO {

    /** 逐题作答，按题目 sort_no 升序；未作答的题也会出现（answers 为空） */
    private List<QuestionAnswerVO> items;

    /** 作答的题目顺序（含题干），供前端直接渲染，不用再发一次问卷详情请求 */
    private List<QuestionVO> questions;
}
