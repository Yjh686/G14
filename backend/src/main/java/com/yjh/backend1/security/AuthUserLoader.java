package com.yjh.backend1.security;

import com.yjh.backend1.common.BizException;
import com.yjh.backend1.module.rbac.service.PermissionService;
import com.yjh.backend1.module.user.entity.SysUser;
import com.yjh.backend1.module.user.mapper.SysUserMapper;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 由令牌装配 {@link LoginUser}。
 * <p>
 * 每次请求都会走一遍「解析 → 查用户 → 校验状态与版本 → 取权限快照」。
 * 用户信息本身不做缓存（用户量 < 500，单次主键查询可忽略），
 * 只有权限码集合做了缓存，避免每次请求 4 表 JOIN。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuthUserLoader {

    private final JwtUtil jwtUtil;
    private final SysUserMapper userMapper;
    private final PermissionService permissionService;

    public LoginUser loadFromToken(String token) {
        Claims claims = jwtUtil.parse(token);
        if (claims == null) {
            throw BizException.unauthorized("登录凭证无效或已过期，请重新登录");
        }

        Long userId;
        try {
            userId = Long.valueOf(claims.getSubject());
        } catch (NumberFormatException e) {
            throw BizException.unauthorized("登录凭证格式错误");
        }

        SysUser user = userMapper.selectById(userId);
        if (user == null) {
            throw BizException.unauthorized("账号不存在或已被删除");
        }

        // 令牌版本校验：改密 / 重置 / 禁用后旧令牌立即失效
        int tokenVer = claims.get("ver") instanceof Number n ? n.intValue() : -1;
        int dbVer = user.getTokenVersion() == null ? 0 : user.getTokenVersion();
        if (tokenVer != dbVer) {
            throw BizException.unauthorized("登录状态已失效，请重新登录");
        }

        if (!Integer.valueOf(1).equals(user.getStatus())) {
            throw BizException.forbidden("账号已被禁用，请联系管理员");
        }
        if (!Integer.valueOf(1).equals(user.getAuditStatus())) {
            throw BizException.forbidden("账号尚未通过审核，请联系管理员");
        }

        PermissionService.Snapshot snap = permissionService.load(userId);

        return new LoginUser(
                user.getId(),
                user.getUsername(),
                user.getRealName(),
                snap.roleCode(),
                snap.roleName(),
                Integer.valueOf(1).equals(user.getMustChangePassword()),
                snap.permCodes()
        );
    }
}
