package com.yjh.backend1.module.recovery.controller;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yjh.backend1.common.BizException;
import com.yjh.backend1.common.Result;
import com.yjh.backend1.module.log.service.OperationLogService;
import com.yjh.backend1.module.recovery.entity.SysUserRecovery;
import com.yjh.backend1.module.recovery.mapper.SysUserRecoveryMapper;
import com.yjh.backend1.module.user.entity.SysUser;
import com.yjh.backend1.module.user.mapper.SysUserMapper;
import com.yjh.backend1.security.RequirePerm;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理员清除某用户的密保（含验证锁定）。
 * <p>
 * 为什么需要这个接口：用户可能<b>连密保答案也忘了</b>。此时管理员重置密码能
 * 让他登进来，但如果他不知道答案，找回通道对他永远是锁着的；更糟的是他
 * 连续答错被锁定 30 分钟，期间管理员重置密码也没用 —— 下次再忘，还是只能等。
 * <p>
 * 清除而不是「解锁」：既然他答不上来，那这两个问题对他已经失效了，
 * 留着只会让下一次仍然失败。清掉之后他登录进个人资料页重设一套即可。
 * <p>
 * 权限沿用 {@code user:reset-password}：这两件事在语义上是一件事
 * （「帮这个人重新获得账号访问权」），单独开一个权限码只会让管理员多勾一次
 * 却想不出什么时候该只给其中一个。
 */
@Tag(name = "12 密码找回")
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class RecoveryAdminController {

    private final SysUserRecoveryMapper recoveryMapper;
    private final SysUserMapper userMapper;
    private final OperationLogService operationLogService;

    @DeleteMapping("/{id}/recovery")
    @RequirePerm("user:reset-password")
    public Result<Void> clearRecovery(@PathVariable Long id, HttpServletRequest httpReq) {
        SysUser user = userMapper.selectById(id);
        if (user == null) {
            throw BizException.notFound("用户不存在");
        }
        SysUserRecovery rec = recoveryMapper.selectOne(
                Wrappers.<SysUserRecovery>lambdaQuery().eq(SysUserRecovery::getUserId, id));
        if (rec == null) {
            throw BizException.badRequest("该用户没有设置密保问题");
        }
        recoveryMapper.deleteById(rec.getId());
        operationLogService.write("账号安全", "清除密保", "sys_user", id,
                "管理员清除了用户《" + user.getUsername() + "》的密保问题", httpReq);
        return Result.okMsg("已清除该用户的密保问题，请通知其登录后重新设置");
    }
}
