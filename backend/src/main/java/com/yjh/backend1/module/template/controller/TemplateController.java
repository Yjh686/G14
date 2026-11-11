package com.yjh.backend1.module.template.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import com.yjh.backend1.common.PageResult;
import com.yjh.backend1.common.Result;
import com.yjh.backend1.module.survey.dto.SurveyDetailVO;
import com.yjh.backend1.module.template.dto.AuditTemplateRequest;
import com.yjh.backend1.module.template.dto.ShareTemplateRequest;
import com.yjh.backend1.module.template.dto.TemplateQuery;
import com.yjh.backend1.module.template.dto.TemplateVO;
import com.yjh.backend1.module.template.service.TemplateService;
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
 * 模板中心接口。
 * <p>
 * 三层权限，缺一不可：
 * <ol>
 *   <li><b>方法级</b>：{@code @RequirePerm} 声明「能不能调这个接口」</li>
 *   <li><b>视角级</b>：{@code scope=mine/audit} 在服务层再校验一次权限，
 *       防止「只有 browse 权限的用户传 scope=audit 看到待审队列」</li>
 *   <li><b>数据级</b>：{@code requireOwned} 校验模板归属，防止「教师 A 下架教师 B 的模板」</li>
 * </ol>
 * 只用一个 Controller 而不是按读者拆三个：这些接口共享同一个 URL 前缀与同一套 VO，
 * 拆开只会让「哪些路径存在」这件事散落在多个文件里。
 */
@Tag(name = "08 模板中心")
@RestController
@RequestMapping("/api/templates")
@RequiredArgsConstructor
public class TemplateController {

    private final TemplateService templateService;

    // ---------------- 浏览 ----------------

    /**
     * 模板列表。scope 默认 browse（只看已通过）；
     * mine=我共享的（全部状态）；audit=待审核队列（需 template:audit）。
     */
    @GetMapping
    @RequirePerm({"template:browse", "template:share", "template:audit"})
    public Result<PageResult<TemplateVO>> page(TemplateQuery query) {
        return Result.ok(templateService.page(query));
    }

    /** 模板详情（含题目结构快照） */
    @GetMapping("/{id}")
    @RequirePerm({"template:browse", "template:share", "template:audit"})
    public Result<TemplateVO> detail(@PathVariable Long id) {
        return Result.ok(templateService.detail(id));
    }

    // ---------------- 共享者 ----------------

    /** 把自己的问卷共享为模板（提交后进入待审核） */
    @PostMapping
    @RequirePerm("template:share")
    public Result<TemplateVO> share(@Valid @RequestBody ShareTemplateRequest req,
                                    HttpServletRequest httpReq) {
        return Result.ok(templateService.share(req, httpReq), "已提交审核，请等待管理员审核");
    }

    /**
     * 重新提交被驳回的模板。
     * 用 POST 而不是 PUT /{id}：这不是「编辑模板内容」，而是「把状态推回待审」，
     * 语义上是一次动作，复用 PUT 会让人以为可以顺手改字段。
     */
    @PostMapping("/{id}/resubmit")
    @RequirePerm("template:share")
    public Result<TemplateVO> resubmit(@PathVariable Long id, HttpServletRequest httpReq) {
        return Result.ok(templateService.resubmit(id, httpReq), "已重新提交审核");
    }

    /** 下架自己的模板（已下架的不再出现在模板中心，已用它建的问卷不受影响） */
    @PostMapping("/{id}/offline")
    @RequirePerm("template:offline")
    public Result<TemplateVO> offline(@PathVariable Long id, HttpServletRequest httpReq) {
        return Result.ok(templateService.offline(id, httpReq), "模板已下架");
    }

    /** 删除模板（仅限已驳回或已下架） */
    @DeleteMapping("/{id}")
    @RequirePerm("template:share")
    public Result<Void> delete(@PathVariable Long id, HttpServletRequest httpReq) {
        templateService.delete(id, httpReq);
        return Result.okMsg("模板已删除");
    }

    // ---------------- 审核 ----------------

    /** 审核模板：通过 / 驳回（驳回必填原因） */
    @PutMapping("/{id}/audit")
    @RequirePerm("template:audit")
    public Result<TemplateVO> audit(@PathVariable Long id,
                                    @Valid @RequestBody AuditTemplateRequest req,
                                    HttpServletRequest httpReq) {
        return Result.ok(templateService.audit(id, req, httpReq), "审核完成");
    }

    // ---------------- 使用与评分 ----------------

    /** 使用模板创建自己的问卷草稿，返回新建的问卷详情 */
    @PostMapping("/{id}/use")
    @RequirePerm("template:use")
    public Result<SurveyDetailVO> use(@PathVariable Long id, HttpServletRequest httpReq) {
        return Result.ok(templateService.use(id, httpReq), "已根据模板创建问卷草稿");
    }

    /**
     * 给模板评分（1~5 星，一人一票，重复调用即为改分）。
     * <p>
     * 评分放在 query 参数而不是请求体：它只有一个整数，
     * 为它定义一个 {@code {"score":4}} 的请求体类会让前端多写一行、后端多一个无人复用的 DTO。
     */
    @PutMapping("/{id}/rating")
    @RequirePerm("template:rate")
    public Result<TemplateVO> rate(@PathVariable Long id,
                                  @RequestParam int score,
                                  HttpServletRequest httpReq) {
        return Result.ok(templateService.rate(id, score, httpReq), "评分已提交");
    }
}
