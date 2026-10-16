package com.g14.g14.login.controller;

import com.g14.g14.login.common.Result;
import com.g14.g14.login.dto.ClassVO;
import com.g14.g14.login.dto.QuestionnaireVO;
import com.g14.g14.login.service.PermissionService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 数据级权限演示端点。
 *
 * <p>班级与问卷模块本体尚未交付，这里提供只读的「查看」端点，
 * 用于真实地验证需求 4 的细粒度权限规则：
 * <ul>
 *   <li>老师只能查看自己创建的班级 / 问卷；</li>
 *   <li>学生只能查看自己所在班级的（已发布）问卷；</li>
 *   <li>管理员全部可见。</li>
 * </ul>
 *
 * <p>越权与不存在返回<b>完全相同</b>的 404 响应（40402 / 40403），防止 ID 枚举。
 * 本控制器不标注 {@code @RequireRole} —— 因为它对三种角色都开放，
 * 真正的拦截发生在 {@link PermissionService} 的数据层。
 */
@RestController
@RequestMapping("/api/permission-demo")
public class PermissionDemoController {

    private final PermissionService permissionService;

    public PermissionDemoController(PermissionService permissionService) {
        this.permissionService = permissionService;
    }

    /**
     * 查看班级。
     *
     * <pre>
     * GET /api/permission-demo/class/{id}/view
     * 200 → { code:200, data:{ id, name, inviteCode, teacherId, createdAt } }
     * 404 → { code:40402, message:"班级不存在或无权访问" }   // 不存在与越权同码同文案
     * </pre>
     */
    @GetMapping("/class/{id}/view")
    public Result<ClassVO> viewClass(@PathVariable("id") Long id) {
        return Result.ok(ClassVO.from(permissionService.requireClassVisible(id)));
    }

    /**
     * 查看问卷。
     *
     * <pre>
     * GET /api/permission-demo/questionnaire/{id}/view
     * 200 → { code:200, data:{ id, classId, title, creatorId, status, published } }
     * 404 → { code:40403, message:"问卷不存在或不可见" }   // 草稿 / 越权 / 不存在同码
     * </pre>
     */
    @GetMapping("/questionnaire/{id}/view")
    public Result<QuestionnaireVO> viewQuestionnaire(@PathVariable("id") Long id) {
        return Result.ok(QuestionnaireVO.from(permissionService.requireQuestionnaireVisible(id)));
    }
}
