package com.g14.g14.login.controller;

import com.g14.g14.login.common.Result;
import com.g14.g14.login.dto.CreateUserRequest;
import com.g14.g14.login.dto.PageResult;
import com.g14.g14.login.dto.ResetPasswordRequest;
import com.g14.g14.login.dto.UserQuery;
import com.g14.g14.login.dto.UserVO;
import com.g14.g14.login.enums.RoleEnum;
import com.g14.g14.login.security.RequireRole;
import com.g14.g14.login.service.AdminService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;

/**
 * 管理员接口。
 *
 * <p>类级 {@link RequireRole}{@code (RoleEnum.ADMIN)} 统一保护本控制器下所有接口；
 * 非管理员访问由 {@code AuthInterceptor} 直接拦截并返回 403/40300。
 *
 * <p>注意方法约定（前端契约测试逐条断言）：
 * <ul>
 *   <li>重置密码是 <b>POST</b>，不是 PUT（用 PUT 会得到 400/40000）；</li>
 *   <li>启用 / 禁用是 <b>PUT</b>，且开关走 <b>query 参数 {@code enabled}</b> 而不是请求体。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/admin")
@RequireRole(RoleEnum.ADMIN)
@Validated
public class AdminController {

    private final AdminService adminService;

    public AdminController(AdminService adminService) {
        this.adminService = adminService;
    }

    /**
     * 开通账号。
     *
     * <pre>
     * POST /api/admin/users
     * { "account":"T1001", "realName":"老师甲", "role":"TEACHER",
     *   "phone":"13800000001", "initialPassword":null }
     *
     * 200 → data:{ id, account, realName, role, ... }
     * 409 → 40901 账号已存在 / 40902 手机号已被占用
     * </pre>
     */
    @PostMapping("/users")
    public Result<UserVO> createUser(@Valid @RequestBody CreateUserRequest request) {
        return Result.ok("账号开通成功", adminService.createUser(request));
    }

    /**
     * 重置指定用户密码。
     *
     * <pre>
     * POST /api/admin/users/{id}/password/reset
     * { "newPassword": "Reset@2024" }   // 传 null = 恢复系统初始密码
     *
     * 200 → { code:200, message:"密码已重置", data:null }
     * 404 → 40401 用户不存在
     * </pre>
     */
    @PostMapping("/users/{id}/password/reset")
    public Result<Void> resetPassword(@PathVariable("id") Long id,
                                      @RequestBody(required = false) ResetPasswordRequest request) {
        adminService.resetPassword(id, request == null ? null : request.getNewPassword());
        return Result.ok("密码已重置", null);
    }

    /**
     * 启用 / 禁用账号。
     *
     * <pre>
     * PUT /api/admin/users/{id}/status?enabled=false
     *
     * 200 → data:{ id, account, ..., status:0 }
     * 禁用后该用户在线令牌立即失效（后续请求 403/40301）
     * </pre>
     */
    @PutMapping("/users/{id}/status")
    public Result<UserVO> updateStatus(@PathVariable("id") Long id,
                                       @RequestParam("enabled") Boolean enabled) {
        return Result.ok("状态更新成功", adminService.updateStatus(id, Boolean.TRUE.equals(enabled)));
    }

    /**
     * 用户列表（分页 + 条件筛选）。
     *
     * <pre>GET /api/admin/users?pageNum=1&amp;pageSize=10&amp;role=STUDENT&amp;keyword=2021</pre>
     * <p>pageSize 上限 100，超出会被截断并在响应中回显生效值。
     */
    @GetMapping("/users")
    public Result<PageResult<UserVO>> listUsers(UserQuery query) {
        return Result.ok(adminService.listUsers(query));
    }
}
