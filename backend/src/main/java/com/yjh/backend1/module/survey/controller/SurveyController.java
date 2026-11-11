package com.yjh.backend1.module.survey.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import com.yjh.backend1.common.PageResult;
import com.yjh.backend1.common.Result;
import com.yjh.backend1.module.survey.dto.QuestionBatchSaveRequest;
import com.yjh.backend1.module.survey.dto.SurveyDetailVO;
import com.yjh.backend1.module.survey.dto.SurveyQuery;
import com.yjh.backend1.module.survey.dto.SurveySaveRequest;
import com.yjh.backend1.module.survey.dto.SurveyVO;
import com.yjh.backend1.module.survey.service.SurveyService;
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

/**
 * 问卷管理接口。
 * <p>
 * 权限说明：{@code @RequirePerm} 只声明「方法级」权限，数据级归属校验
 * 统一在 {@link SurveyService#requireOwned} 里做。两者缺一不可：
 * 只查权限会出现「教师 A 编辑教师 B 的问卷」。
 */
@Tag(name = "04 问卷管理")
@RestController
@RequestMapping("/api/surveys")
@RequiredArgsConstructor
public class SurveyController {

    private final SurveyService surveyService;

    /** 问卷列表：默认仅本人创建，scope=all 需 survey:list:all */
    @GetMapping
    @RequirePerm({"survey:list:self", "survey:list:all"})
    public Result<PageResult<SurveyVO>> page(SurveyQuery query) {
        return Result.ok(surveyService.page(query));
    }

    /** 问卷详情（含题目） */
    @GetMapping("/{id}")
    @RequirePerm({"survey:update:self", "survey:update:all", "survey:preview"})
    public Result<SurveyDetailVO> detail(@PathVariable Long id) {
        return Result.ok(surveyService.detail(id));
    }

    /**
     * 按分享码取问卷（分享链接落地页，登录后可用）。
     * 放在 /{id} 之前的固定路径，避免被路径变量吞掉。
     */
    @GetMapping("/share/{shareCode}")
    @RequirePerm("survey:fill")
    public Result<SurveyDetailVO> detailByShareCode(@PathVariable String shareCode) {
        return Result.ok(surveyService.detailByShareCode(shareCode));
    }

    /** 预览：学生视角渲染，不产生答卷 */
    @GetMapping("/{id}/preview")
    @RequirePerm("survey:preview")
    public Result<SurveyDetailVO> preview(@PathVariable Long id) {
        return Result.ok(surveyService.preview(id));
    }

    /** 新建问卷（草稿） */
    @PostMapping
    @RequirePerm("survey:create")
    public Result<SurveyDetailVO> create(@Valid @RequestBody SurveySaveRequest req,
                                         HttpServletRequest httpReq) {
        return Result.ok(surveyService.create(req, httpReq), "问卷已创建");
    }

    /** 修改问卷元信息；已发布的问卷仅允许改标题/简介/截止时间 */
    @PutMapping("/{id}")
    @RequirePerm({"survey:update:self", "survey:update:all"})
    public Result<SurveyDetailVO> update(@PathVariable Long id,
                                         @Valid @RequestBody SurveySaveRequest req,
                                         HttpServletRequest httpReq) {
        return Result.ok(surveyService.update(id, req, httpReq), "问卷已保存");
    }

    /** 删除问卷（逻辑删除） */
    @DeleteMapping("/{id}")
    @RequirePerm({"survey:delete:self", "survey:delete:all"})
    public Result<Void> delete(@PathVariable Long id,
                               @RequestParam(defaultValue = "false") boolean confirm,
                               HttpServletRequest httpReq) {
        surveyService.delete(id, confirm, httpReq);
        return Result.okMsg("问卷已删除");
    }

    /** 复制问卷为新草稿（发布后改结构的唯一姿势） */
    @PostMapping("/{id}/copy")
    @RequirePerm("survey:copy")
    public Result<SurveyDetailVO> copy(@PathVariable Long id, HttpServletRequest httpReq) {
        return Result.ok(surveyService.copy(id, httpReq), "已复制为新草稿");
    }

    /**
     * 整卷保存题目（差量：有 id 更新、无 id 新增、多余的删除）。
     * 请求体：{@code {"questions":[{...},{...}]}}
     */
    @PutMapping("/{id}/questions")
    @RequirePerm({"survey:update:self", "survey:update:all"})
    public Result<SurveyDetailVO> saveQuestions(@PathVariable Long id,
                                                @Valid @RequestBody QuestionBatchSaveRequest req,
                                                HttpServletRequest httpReq) {
        return Result.ok(surveyService.saveQuestions(id, req.getQuestions(), httpReq), "题目已保存");
    }

    /** 发布问卷：生成分享码，题目结构随即锁定 */
    @PostMapping("/{id}/publish")
    @RequirePerm({"survey:publish:self", "survey:publish:all"})
    public Result<SurveyVO> publish(@PathVariable Long id, HttpServletRequest httpReq) {
        return Result.ok(surveyService.publish(id, httpReq), "问卷已发布");
    }

    /** 暂停回收 */
    @PostMapping("/{id}/pause")
    @RequirePerm({"survey:publish:self", "survey:publish:all"})
    public Result<SurveyVO> pause(@PathVariable Long id, HttpServletRequest httpReq) {
        return Result.ok(surveyService.pause(id, httpReq), "已暂停回收");
    }

    /** 恢复回收 */
    @PostMapping("/{id}/resume")
    @RequirePerm({"survey:publish:self", "survey:publish:all"})
    public Result<SurveyVO> resume(@PathVariable Long id, HttpServletRequest httpReq) {
        return Result.ok(surveyService.resume(id, httpReq), "已恢复回收");
    }

    /** 结束问卷（不可逆） */
    @PostMapping("/{id}/close")
    @RequirePerm({"survey:publish:self", "survey:publish:all"})
    public Result<SurveyVO> close(@PathVariable Long id, HttpServletRequest httpReq) {
        return Result.ok(surveyService.close(id, httpReq), "问卷已结束");
    }
}
