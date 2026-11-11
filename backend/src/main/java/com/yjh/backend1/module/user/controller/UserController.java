package com.yjh.backend1.module.user.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import com.yjh.backend1.common.PageResult;
import com.yjh.backend1.common.Result;
import com.yjh.backend1.module.user.dto.AuditUserRequest;
import com.yjh.backend1.module.user.dto.CreateUserRequest;
import com.yjh.backend1.module.user.dto.ImportResultVO;
import com.yjh.backend1.module.user.dto.UpdateUserRequest;
import com.yjh.backend1.module.user.dto.UserQuery;
import com.yjh.backend1.module.user.dto.UserVO;
import com.yjh.backend1.module.user.service.UserImportService;
import com.yjh.backend1.module.user.service.UserService;
import com.yjh.backend1.security.RequirePerm;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
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
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * 用户管理接口（ADMIN）。
 */
@Tag(name = "02 用户管理")
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;
    private final UserImportService userImportService;

    /** 分页查询用户，支持关键词/状态/审核状态/角色过滤 */
    @GetMapping
    @RequirePerm("user:list")
    public Result<PageResult<UserVO>> page(UserQuery query) {
        return Result.ok(userService.page(query));
    }

    /** 用户详情 */
    @GetMapping("/{id}")
    @RequirePerm({"user:list", "user:audit"})
    public Result<UserVO> detail(@PathVariable Long id) {
        return Result.ok(userService.detail(id));
    }

    /** 新建用户（不填密码则用默认密码 + 强制首登改密） */
    @PostMapping
    @RequirePerm("user:create")
    public Result<UserVO> create(@Valid @RequestBody CreateUserRequest req, HttpServletRequest httpReq) {
        return Result.ok(userService.create(req, httpReq), "用户创建成功");
    }

    /** 修改用户资料/角色 */
    @PutMapping("/{id}")
    @RequirePerm("user:update")
    public Result<UserVO> update(@PathVariable Long id,
                                 @Valid @RequestBody UpdateUserRequest req,
                                 HttpServletRequest httpReq) {
        return Result.ok(userService.update(id, req, httpReq), "用户信息已更新");
    }

    /** 删除用户（逻辑删除，同时踢下线） */
    @DeleteMapping("/{id}")
    @RequirePerm("user:delete")
    public Result<Void> delete(@PathVariable Long id, HttpServletRequest httpReq) {
        userService.delete(id, httpReq);
        return Result.okMsg("用户已删除");
    }

    /** 启用 / 禁用 */
    @PutMapping("/{id}/status")
    @RequirePerm("user:status")
    public Result<Void> changeStatus(@PathVariable Long id,
                                     @RequestParam Integer status,
                                     HttpServletRequest httpReq) {
        userService.changeStatus(id, status, httpReq);
        return Result.okMsg(status == 1 ? "账号已启用" : "账号已禁用");
    }

    /** 审核注册申请：1=通过 2=驳回（驳回必须填意见） */
    @PutMapping("/{id}/audit")
    @RequirePerm("user:audit")
    public Result<Void> audit(@PathVariable Long id,
                              @Valid @RequestBody AuditUserRequest req,
                              HttpServletRequest httpReq) {
        userService.audit(id, req, httpReq);
        return Result.okMsg(Integer.valueOf(1).equals(req.getAuditStatus()) ? "已通过审核" : "已驳回申请");
    }

    /**
     * 重置密码为系统默认密码，并要求该用户下次登录强制改密。
     *
     * @return 默认密码明文，供管理员当面告知用户
     */
    @PostMapping("/{id}/reset-password")
    @RequirePerm("user:reset-password")
    public Result<String> resetPassword(@PathVariable Long id, HttpServletRequest httpReq) {
        String pwd = userService.resetPassword(id, httpReq);
        return Result.ok(pwd, "密码已重置，请通知用户首次登录后立即修改");
    }

    /**
     * Excel 批量导入。
     * <p>
     * 返回的是「成功几条、失败几条、失败在第几行、为什么」，
     * 而不是简单的一句成功或失败 —— 见 {@link ImportResultVO} 的说明。
     * <p>
     * 用 {@code @RequestPart} 而不是 {@code @RequestParam}：前者能明确按 multipart
     * 部件名绑定，文件字段名固定为 {@code file}，前端 FormData 也照这个名传。
     */
    @PostMapping("/import")
    @RequirePerm("user:import")
    public Result<ImportResultVO> importUsers(@RequestPart("file") MultipartFile file,
                                              HttpServletRequest httpReq) {
        ImportResultVO result = userImportService.importUsers(file, httpReq);
        String msg = result.getFailed() == 0
                ? "全部导入成功，共 " + result.getSuccess() + " 条"
                : "导入完成：成功 " + result.getSuccess() + " 条，失败 " + result.getFailed() + " 条";
        return Result.ok(result, msg);
    }

    /**
     * 下载导入模板。
     * <p>
     * 与导出接口一样直接写流，不套 {@code Result} —— xlsx 是二进制，
     * 包一层 JSON 就没法用了。
     */
    @GetMapping("/import/template")
    @RequirePerm("user:import")
    public void downloadTemplate(HttpServletResponse response) throws IOException {
        userImportService.writeTemplate(response);
    }
}
