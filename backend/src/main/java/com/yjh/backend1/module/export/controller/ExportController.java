package com.yjh.backend1.module.export.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import com.yjh.backend1.module.export.service.ExportService;
import com.yjh.backend1.security.RequirePerm;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

/**
 * 导出接口（FR-EXP）。
 * <p>
 * 返回的是二进制流而不是统一 JSON 结构，这是唯一一类不遵守
 * 「{@code {code,msg,data}} 响应体」约定的接口 —— 前端要按 blob 处理。
 * 前端封装见 {@code src/api/survey.ts} 的 {@code surveyApi.exportAnswers}。
 */
@Tag(name = "07 数据导出")
@RestController
@RequestMapping("/api/surveys/{surveyId}/export")
@RequiredArgsConstructor
public class ExportController {

    private final ExportService exportService;

    /**
     * 导出问卷答卷为 xlsx。
     * <p>
     * 失败时不写响应体（可能已经写了一部分），由 {@code GlobalExceptionHandler} 兜底；
     * 但只要 {@code response.isCommitted()} 为 true，全局处理器也无法改状态码，
     * 此时前端会拿到一个损坏的文件。实践中触发概率低（失败多发生在准备阶段），
     * 更彻底的做法是先写临时文件再整体拷贝，代价是多一份磁盘 IO。
     */
    @GetMapping("/answers")
    @RequirePerm({"export:self", "export:all"})
    public void exportAnswers(@PathVariable Long surveyId,
                              HttpServletResponse response,
                              HttpServletRequest request) throws IOException {
        exportService.exportSurvey(surveyId, response, request);
    }
}
