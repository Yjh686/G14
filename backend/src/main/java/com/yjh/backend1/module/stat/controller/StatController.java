package com.yjh.backend1.module.stat.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import com.yjh.backend1.common.Result;
import com.yjh.backend1.module.stat.dto.QuestionStatsVO;
import com.yjh.backend1.module.stat.dto.SurveyStatsVO;
import com.yjh.backend1.module.stat.service.StatService;
import com.yjh.backend1.security.RequirePerm;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 统计接口（FR-STAT）。
 * <p>
 * 只有读操作，所以没有写日志的必要 —— 但导出有日志，见 ExportController。
 */
@Tag(name = "06 统计分析")
@RestController
@RequestMapping("/api/surveys/{surveyId}/stats")
@RequiredArgsConstructor
public class StatController {

    private final StatService statService;

    /** 整卷统计：卷头概览 + 逐题数据 */
    @GetMapping("")
    @RequirePerm({"stat:read:self", "stat:read:all"})
    public Result<SurveyStatsVO> surveyStats(@PathVariable Long surveyId) {
        return Result.ok(statService.surveyStats(surveyId));
    }

    /** 单题统计：前端图表单独刷新时用 */
    @GetMapping("/questions/{questionId}")
    @RequirePerm({"stat:read:self", "stat:read:all"})
    public Result<QuestionStatsVO> questionStats(@PathVariable Long surveyId,
                                                 @PathVariable Long questionId) {
        return Result.ok(statService.singleQuestionStats(surveyId, questionId));
    }
}
