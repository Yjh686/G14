package com.g14.g14.login.controller;

import com.g14.g14.login.common.Result;
import com.g14.g14.login.dto.ChangePasswordRequest;
import com.g14.g14.login.dto.LoginRequest;
import com.g14.g14.login.dto.LoginResponse;
import com.g14.g14.login.dto.UserVO;
import com.g14.g14.login.enums.RoleEnum;
import com.g14.g14.login.security.AuthContext;
import com.g14.g14.login.security.LoginUser;
import com.g14.g14.login.security.PublicApi;
import com.g14.g14.login.security.RequireRole;
import com.g14.g14.login.service.AuthService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import javax.validation.Valid;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 认证接口。
 *
 * <p>除 {@code /api/auth/login}（标注 {@link PublicApi}）外，
 * 其余接口均需携带 {@code Authorization: Bearer <token>}。
 */
@RestController
@RequestMapping("/api/auth")
@Validated
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /**
     * 统一登录。后端根据账号自动识别角色（学号→学生，工号/手机号→老师，admin→管理员）。
     *
     * <pre>
     * POST /api/auth/login
     * { "account": "2021001", "password": "Init@1234" }
     *
     * 200 → { code:200, message:"登录成功",
     *         data:{ token, tokenType:"Bearer", expiresIn:7200, mustChangePassword:true, user:{...} } }
     * 401 → { code:40104, message:"账号或密码错误" }
     * 403 → { code:40301, message:"账号已被禁用" } / { code:40303, ... 锁定 }
     * </pre>
     */
    @PublicApi
    @PostMapping("/login")
    public Result<LoginResponse> login(@Valid @RequestBody LoginRequest request,
                                       HttpServletRequest httpRequest) {
        return Result.ok("登录成功", authService.login(request, httpRequest));
    }

    /**
     * 登出：当前令牌立即进黑名单，后续使用返回 401/40103。
     *
     * <pre>POST /api/auth/logout → { code:200, message:"已登出", data:null }</pre>
     */
    @PostMapping("/logout")
    public Result<Void> logout() {
        authService.logout();
        return Result.ok("已登出", null);
    }

    /**
     * 获取当前登录用户信息（前端刷新页面后用它恢复会话）。
     *
     * <pre>GET /api/auth/me → { code:200, data:{ id, account, realName, role, status, phone(脱敏), mustChangePassword, createdAt } }</pre>
     */
    @GetMapping("/me")
    public Result<UserVO> me() {
        return Result.ok(authService.currentUser());
    }

    /**
     * 修改密码（首次登录强制改密复用本接口）。
     *
     * <pre>
     * POST /api/auth/password
     * { "oldPassword": "Init@1234", "newPassword": "Student@123" }
     *
     * 200 → data:{ token(新令牌), mustChangePassword:false, user:{...} }
     * 400 → 40001 弱口令 / 40002 原密码错误 / 40003 新旧密码相同
     * </pre>
     */
    @PostMapping("/password")
    public Result<LoginResponse> changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        return Result.ok("密码修改成功", authService.changePassword(request));
    }

    /**
     * 老师专属演示接口 —— 用于验证「角色维」的接口级鉴权。
     *
     * <pre>
     * GET /api/auth/teacher-only-demo
     * 学生调用 → 403/40300（无权限）
     * 老师 / 管理员调用 → 200
     * 未完成首次改密时 → 403/40302（被强制改密拦截，早于角色校验）
     * </pre>
     */
    @RequireRole(RoleEnum.TEACHER)
    @GetMapping("/teacher-only-demo")
    public Result<Map<String, Object>> teacherOnlyDemo() {
        LoginUser me = AuthContext.require();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("message", "老师专属接口调用成功");
        data.put("userId", me.getUserId());
        data.put("account", me.getAccount());
        data.put("role", me.getRole().name());
        return Result.ok(data);
    }
}
