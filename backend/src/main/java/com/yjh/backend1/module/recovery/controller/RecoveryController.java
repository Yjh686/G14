package com.yjh.backend1.module.recovery.controller;

import com.yjh.backend1.common.Result;
import com.yjh.backend1.module.recovery.dto.RecoveryLookupRequest;
import com.yjh.backend1.module.recovery.dto.RecoveryQuestionsVO;
import com.yjh.backend1.module.recovery.dto.RecoveryResetRequest;
import com.yjh.backend1.module.recovery.dto.RecoverySetupRequest;
import com.yjh.backend1.module.recovery.service.RecoveryService;
import io.swagger.v3.oas.annotations.tags.Tag;
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
 * 密码找回（密保问题）与密保管理。
 *
 * <h3>这个控制器有两种权限形态，故意的</h3>
 * <ul>
 *   <li>{@code /lookup} 与 {@code /reset} <b>免登录</b> —— 忘记密码的人恰恰
 *       进不来。它们被加进 {@code AuthInterceptor.WHITE_LIST}。</li>
 *   <li>{@code /mine}、{@code /setup} <b>需登录</b> —— 设置密保的前提是
 *       你已经能证明自己是本人（即已登录）。</li>
 * </ul>
 * 免登录的两个接口是整站唯一「拿着账号就能改密码」的路径，所以
 * Service 里做了完整的失败计数、锁定与流水记录，这里不再叠一层注解，
 * 免得读代码的人以为「没注解 = 没防护」。
 */
@Tag(name = "12 密码找回")
@RestController
@RequestMapping("/api/auth/recovery")
@RequiredArgsConstructor
public class RecoveryController {

    private final RecoveryService recoveryService;

    /**
     * 第一步：用账号换密保问题（免登录）。
     * <p>
     * 会区分「账号不存在 / 没设置密保 / 已锁定」，因为这三种情况的
     * 下一步完全不同；代价是泄露了「这个账号存在」，但换来的问题文本
     * 对攻击者没有额外价值。
     */
    @PostMapping("/lookup")
    public Result<RecoveryQuestionsVO> lookup(@Valid @RequestBody RecoveryLookupRequest req,
                                              HttpServletRequest httpReq) {
        return Result.ok(recoveryService.lookup(req, httpReq));
    }

    /**
     * 第二步：答对两问并重设密码（免登录）。
     * <p>
     * 成功后该用户所有已登录设备的令牌立即失效（token_version + 1）。
     */
    @PostMapping("/reset")
    public Result<Void> reset(@Valid @RequestBody RecoveryResetRequest req,
                              HttpServletRequest httpReq) {
        recoveryService.reset(req, httpReq);
        return Result.okMsg("密码已重置，请用新密码登录");
    }

    /** 我是否已设置密保问题（个人资料页用来决定显示「设置」还是「修改」） */
    @GetMapping("/mine")
    public Result<Boolean> mine() {
        return Result.ok(recoveryService.hasRecovery());
    }

    /** 我设置的两个密保问题题面（不含答案） */
    @GetMapping("/my-questions")
    public Result<RecoveryQuestionsVO> myQuestions() {
        return Result.ok(recoveryService.myQuestions());
    }

    /** 设置 / 修改自己的密保问题（需登录） */
    @PutMapping("/setup")
    public Result<Void> setup(@Valid @RequestBody RecoverySetupRequest req,
                              HttpServletRequest httpReq) {
        recoveryService.setup(req, httpReq);
        return Result.okMsg("密保问题已保存");
    }
}
