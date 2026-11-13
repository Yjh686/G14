package com.yjh.backend1.module.recovery.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yjh.backend1.common.PasswordPolicy;
import com.yjh.backend1.module.config.service.ConfigService;
import com.yjh.backend1.module.rbac.service.PermissionService;
import com.yjh.backend1.module.recovery.entity.SysUserRecovery;
import com.yjh.backend1.module.recovery.mapper.SysUserRecoveryMapper;
import com.yjh.backend1.module.user.entity.SysUser;
import com.yjh.backend1.module.user.mapper.SysUserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 找回密码的写操作，单独成 Bean。
 *
 * <h3>为什么必须拆出来（踩过的真实缺陷）</h3>
 * 最初把「失败计数 +1」和「抛出 400 提示」写在同一个 {@code @Transactional}
 * 方法里，实测 5 次错答后提示始终是「还可尝试 4 次」，锁定从未触发 ——
 * 因为 {@code BizException} 是 RuntimeException，抛出后整个事务回滚，
 * 刚写进去的失败计数跟着一起没了。等于这套防爆破形同虚设，而且从接口
 * 返回上看不出来（错误提示是正确的，只是计数永远不涨）。
 *
 * <p>修法与压测阶段的草稿死锁同源：<b>把需要留下的写操作放进独立事务</b>。
 * {@code recordFailure} 用 {@code REQUIRES_NEW}，让它在调用方事务（或没有
 * 事务）之外单独提交，之后调用方再抛异常也不会波及它。
 *
 * <p>这类方法必须放在<b>另一个 Bean</b> 里：Spring 的声明式事务基于代理，
 * 同类内部调用不会经过代理，注解直接失效。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RecoveryWriteService {

    private final SysUserRecoveryMapper recoveryMapper;
    private final SysUserMapper userMapper;
    private final PermissionService permissionService;
    private final ConfigService configService;
    private final BCryptPasswordEncoder passwordEncoder;

    /**
     * 记一次失败；达到上限则同时置锁定。
     *
     * <p>{@code REQUIRES_NEW}：独立提交，调用方随后抛 400 也不会被回滚。
     *
     * @return 本次之后剩余的尝试次数（0 表示已锁定）
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public int recordFailure(Long recoveryId, int maxFail, int lockMinutes) {
        SysUserRecovery cur = recoveryMapper.selectById(recoveryId);
        int fails = (cur == null || cur.getFailCount() == null ? 0 : cur.getFailCount()) + 1;
        SysUserRecovery upd = new SysUserRecovery();
        upd.setId(recoveryId);
        if (fails >= maxFail) {
            upd.setFailCount(0);
            upd.setLockedUntil(LocalDateTime.now().plusMinutes(lockMinutes));
            recoveryMapper.updateById(upd);
            log.info("密保验证失败达上限，锁定 {} 分钟 recoveryId={}", lockMinutes, recoveryId);
            return 0;
        }
        upd.setFailCount(fails);
        recoveryMapper.updateById(upd);
        return maxFail - fails;
    }

    /**
     * 验证通过：重设密码 + 令牌版本 +1 + 清掉密保表的失败计数。
     *
     * <p>整段必须原子：密码改了但 token_version 没加，等于旧令牌还能用；
     * token_version 加了但密码没改，用户会用旧密码登不进来。
     */
    @Transactional(rollbackFor = Exception.class)
    public void applyReset(Long userId, Long recoveryId, String newPassword) {
        SysUser u0 = userMapper.selectById(userId);
        int ver = (u0 == null || u0.getTokenVersion() == null ? 0 : u0.getTokenVersion()) + 1;

        SysUser upd = new SysUser();
        upd.setId(userId);
        upd.setPasswordHash(passwordEncoder.encode(newPassword));
        upd.setTokenVersion(ver);
        // 找回后不再强制改密：刚设的密码就是用户自己选的
        upd.setMustChangePassword(0);
        upd.setLoginFailCount(0);
        upd.setLockedUntil(null);
        userMapper.updateById(upd);
        permissionService.evict(userId);

        SysUserRecovery rUpd = new SysUserRecovery();
        rUpd.setId(recoveryId);
        rUpd.setFailCount(0);
        rUpd.setLockedUntil(null);
        rUpd.setLastVerifyTime(LocalDateTime.now());
        recoveryMapper.updateById(rUpd);

        log.info("密保重置密码成功 userId={} tokenVersion -> {}", userId, ver);
    }

    /** 新密码是否与原密码相同（取当前哈希比对） */
    public boolean sameAsCurrent(Long userId, String newPassword) {
        SysUser u = userMapper.selectById(userId);
        return u != null && u.getPasswordHash() != null
                && passwordEncoder.matches(newPassword, u.getPasswordHash());
    }

    /** 使该用户权限缓存失效（改密后角色/权限快照需重取） */
    public void evictPermissions(Long userId) {
        permissionService.evict(userId);
    }

    /** 按系统配置校验新密码强度，不通过抛 400 */
    public void checkPasswordPolicy(String newPassword) {
        PasswordPolicy.check(newPassword, configService.passwordRules());
    }

    /** 查询当前用户当前哈希（供上层判断「新密码不能与旧密码相同」） */
    public SysUser currentUser(Long userId) {
        return userMapper.selectById(userId);
    }

    /** 仅用于测试/诊断：清掉某用户的密保失败计数 */
    @Transactional(rollbackFor = Exception.class)
    public void clearFail(Long userId) {
        SysUserRecovery rec = recoveryMapper.selectOne(
                Wrappers.<SysUserRecovery>lambdaQuery().eq(SysUserRecovery::getUserId, userId));
        if (rec != null) {
            SysUserRecovery upd = new SysUserRecovery();
            upd.setId(rec.getId());
            upd.setFailCount(0);
            upd.setLockedUntil(null);
            recoveryMapper.updateById(upd);
        }
    }
}
