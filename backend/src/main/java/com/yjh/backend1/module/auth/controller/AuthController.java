package com.yjh.backend1.module.auth.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import com.yjh.backend1.common.Result;
import com.yjh.backend1.module.auth.dto.ChangePasswordRequest;
import com.yjh.backend1.module.auth.dto.LoginRequest;
import com.yjh.backend1.module.auth.dto.LoginResponse;
import com.yjh.backend1.module.auth.dto.MeResponse;
import com.yjh.backend1.module.auth.dto.RegisterRequest;
import com.yjh.backend1.module.auth.dto.UpdateProfileRequest;
import com.yjh.backend1.module.auth.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证接口。
 * <p>
 * 统一前缀 {@code /api/auth}。登录、注册、改密都做了限流与日志留痕。
 */
@Tag(name = "01 认证")
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    /**
     * 登录。成功返回 token 与权限码集合。
     * <p>
     * 失败时故意不区分"账号不存在"和"密码错误"。
     */
    @PostMapping("/login")
    public Result<LoginResponse> login(@Valid @RequestBody LoginRequest req,
                                       HttpServletRequest httpReq) {
        return Result.ok(authService.login(req, httpReq));
    }

    /**
     * 登出。前端清掉本地 token 即可；服务端记一条日志。
     */
    @PostMapping("/logout")
    public Result<Void> logout() {
        authService.logout();
        return Result.okMsg("已退出登录");
    }

    /**
     * 自助注册（学生 / 教师）。注册后需 ADMIN 审核通过才能登录。
     */
    @PostMapping("/register")
    public Result<Void> register(@Valid @RequestBody RegisterRequest req) {
        authService.register(req);
        return Result.okMsg("注册申请已提交，请等待管理员审核");
    }

    /**
     * 修改自己的密码。成功返回新 token（当前设备无需重新登录）。
     */
    @PostMapping("/password")
    public Result<LoginResponse> changePassword(@Valid @RequestBody ChangePasswordRequest req) {
        return Result.ok(authService.changePassword(req), "密码修改成功");
    }

    /**
     * 是否需要强制改密。前端登录后可用它决定是否直接跳转改密页。
     */
    @GetMapping("/password-status")
    public Result<Boolean> passwordStatus() {
        return Result.ok(authService.me().isMustChangePassword());
    }

    /** 当前登录用户资料 */
    @GetMapping("/me")
    public Result<MeResponse> me() {
        return Result.ok(authService.me());
    }

    /** 修改自己的资料（姓名/手机号/邮箱/院系） */
    @PutMapping("/profile")
    public Result<MeResponse> updateProfile(@Valid @RequestBody UpdateProfileRequest req) {
        return Result.ok(authService.updateProfile(req), "资料已更新");
    }
}
