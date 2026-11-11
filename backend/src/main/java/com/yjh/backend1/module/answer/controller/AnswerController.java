package com.yjh.backend1.module.answer.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import com.yjh.backend1.common.PageResult;
import com.yjh.backend1.common.Result;
import com.yjh.backend1.module.answer.dto.AnswerInput;
import com.yjh.backend1.module.answer.dto.AnswerQuery;
import com.yjh.backend1.module.answer.dto.AnswerSheetDetailVO;
import com.yjh.backend1.module.answer.dto.AnswerSheetVO;
import com.yjh.backend1.module.answer.dto.AnswerSummaryVO;
import com.yjh.backend1.module.answer.dto.FillStateVO;
import com.yjh.backend1.module.answer.dto.HallSurveyVO;
import com.yjh.backend1.module.answer.dto.MyAnswerVO;
import com.yjh.backend1.module.answer.dto.SaveDraftRequest;
import com.yjh.backend1.module.answer.dto.SubmitAnswerRequest;
import com.yjh.backend1.module.answer.service.AnswerService;
import com.yjh.backend1.security.RequirePerm;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 填写与答卷接口。
 * <p>
 * 分成三段路径，边界写在路径上而不是靠权限码猜：
 * <ul>
 *   <li>{@code /api/hall/**} —— 学生视角：找问卷、填报、看自己的记录</li>
 *   <li>{@code /api/hall/surveys/{id}/draft} —— 草稿（自己的）</li>
 *   <li>{@code /api/answers/**} —— 发布者视角：看别人的答卷、标记、删除</li>
 * </ul>
 * 发布者的接口全部落在 {@code /api/answers} 下，一眼就能看出「这是会碰到他人数据的接口」，
 * 评审时不需要先翻权限注解才知道风险在哪。
 */
@Tag(name = "05 答题与答卷")
@RestController
@RequiredArgsConstructor
public class AnswerController {

    private final AnswerService answerService;

    // ========================= 学生视角 =========================

    /** 问卷大厅（FR-FILL-01）：可填问卷 + 我的状态 */
    @GetMapping("/api/hall")
    @RequirePerm("survey:fill")
    public Result<PageResult<HallSurveyVO>> hall(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String category) {
        return Result.ok(answerService.hall(page == null ? 1 : page, size == null ? 12 : size, keyword, category));
    }

    /**
     * 进入填写页前取状态（FR-FILL-06 / FR-FILL-08）。
     * <p>已提交、未开始、已暂停、已结束都会在这里体现，前端不必自己拼时间判断。
     */
    @GetMapping("/api/hall/surveys/{surveyId}/fill-state")
    @RequirePerm("survey:fill")
    public Result<FillStateVO> fillState(@PathVariable Long surveyId) {
        return Result.ok(answerService.fillState(surveyId));
    }

    /** 读取自己的草稿（断点续答，FR-FILL-04） */
    @GetMapping("/api/hall/surveys/{surveyId}/draft")
    @RequirePerm("draft:save:self")
    public Result<List<AnswerInput>> getDraft(@PathVariable Long surveyId) {
        return Result.ok(answerService.getDraft(surveyId));
    }

    /** 保存草稿（upsert）。返回服务端记录的保存时间，前端用它显示「已保存于 …」 */
    @PutMapping("/api/hall/surveys/{surveyId}/draft")
    @RequirePerm("draft:save:self")
    public Result<Map<String, Object>> saveDraft(@PathVariable Long surveyId,
                                                 @Valid @RequestBody SaveDraftRequest req) {
        LocalDateTime at = answerService.saveDraft(surveyId, req);
        return Result.ok(Map.of("savedAt", at), "草稿已保存");
    }

    /** 提交答卷（幂等：重复提交返回 409） */
    @PostMapping("/api/hall/surveys/{surveyId}/submit")
    @RequirePerm("survey:fill")
    public Result<AnswerSheetVO> submit(@PathVariable Long surveyId,
                                        @Valid @RequestBody SubmitAnswerRequest req,
                                        HttpServletRequest httpReq) {
        return Result.ok(answerService.submit(surveyId, req, httpReq), "提交成功，感谢你的参与");
    }

    /** 我的填写记录（FR-FILL-07） */
    @GetMapping("/api/hall/my-answers")
    @RequirePerm("answer:read:self")
    public Result<PageResult<MyAnswerVO>> myRecords(
            @RequestParam(required = false) Integer isValid,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return Result.ok(answerService.myRecords(isValid, page == null ? 1 : page, size == null ? 20 : size));
    }

    /** 我的答卷回执/详情：匿名问卷只返回回执，不带作答内容 */
    @GetMapping("/api/hall/my-answers/{sheetId}")
    @RequirePerm("answer:read:self")
    public Result<AnswerSheetDetailVO> myDetail(@PathVariable Long sheetId) {
        return Result.ok(answerService.myDetail(sheetId));
    }

    // ========================= 发布者视角 =========================

    /** 答卷列表（FR-ANS-01 / FR-ANS-06） */
    @GetMapping("/api/answers")
    @RequirePerm({"answer:read:survey:self", "answer:read:survey:all"})
    public Result<PageResult<AnswerSheetVO>> page(AnswerQuery query) {
        return Result.ok(answerService.pageAnswers(query));
    }

    /** 答卷详情（FR-ANS-02） */
    @GetMapping("/api/answers/{sheetId}")
    @RequirePerm({"answer:read:survey:self", "answer:read:survey:all"})
    public Result<AnswerSheetDetailVO> detail(@PathVariable Long sheetId) {
        return Result.ok(answerService.sheetDetail(sheetId));
    }

    /** 回收概览（FR-ANS-05）：已回收 / 有效 / 无效 / 完成率 */
    @GetMapping("/api/surveys/{surveyId}/answer-summary")
    @RequirePerm({"answer:read:survey:self", "answer:read:survey:all"})
    public Result<AnswerSummaryVO> summary(@PathVariable Long surveyId) {
        return Result.ok(answerService.summary(surveyId));
    }

    /** 标记无效（FR-ANS-03），reason 可选 */
    @PutMapping("/api/answers/{sheetId}/invalidate")
    @RequirePerm("answer:invalidate")
    public Result<AnswerSheetVO> invalidate(@PathVariable Long sheetId,
                                            @RequestParam(required = false) String reason,
                                            HttpServletRequest httpReq) {
        return Result.ok(answerService.invalidate(sheetId, reason, httpReq), "已标记为无效");
    }

    /** 恢复有效 */
    @PutMapping("/api/answers/{sheetId}/restore")
    @RequirePerm("answer:invalidate")
    public Result<AnswerSheetVO> restore(@PathVariable Long sheetId, HttpServletRequest httpReq) {
        return Result.ok(answerService.restore(sheetId, httpReq), "已恢复为有效");
    }

    /** 删除答卷（FR-ANS-04，软删，仅 ADMIN） */
    @DeleteMapping("/api/answers/{sheetId}")
    @RequirePerm("answer:delete")
    public Result<Void> delete(@PathVariable Long sheetId, HttpServletRequest httpReq) {
        answerService.deleteSheet(sheetId, httpReq);
        return Result.okMsg("答卷已删除");
    }
}
