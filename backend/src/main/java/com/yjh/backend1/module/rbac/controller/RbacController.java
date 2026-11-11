package com.yjh.backend1.module.rbac.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yjh.backend1.common.BizException;
import com.yjh.backend1.common.Result;
import com.yjh.backend1.module.log.service.OperationLogService;
import com.yjh.backend1.module.rbac.entity.SysPermission;
import com.yjh.backend1.module.rbac.entity.SysUserPermission;
import com.yjh.backend1.module.rbac.mapper.SysPermissionMapper;
import com.yjh.backend1.module.rbac.mapper.SysUserPermissionMapper;
import com.yjh.backend1.module.rbac.service.PermissionService;
import com.yjh.backend1.module.rbac.entity.SysRole;
import com.yjh.backend1.module.rbac.mapper.SysRoleMapper;
import com.yjh.backend1.security.RequirePerm;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 角色与权限配置接口（ADMIN）。
 */
@Tag(name = "03 角色与权限")
@RestController
@RequestMapping("/api/rbac")
@RequiredArgsConstructor
public class RbacController {

    private final SysRoleMapper roleMapper;
    private final SysPermissionMapper permissionMapper;
    private final SysUserPermissionMapper userPermMapper;
    private final PermissionService permissionService;
    private final OperationLogService operationLogService;

    /** 全部角色（本期内置三角色） */
    @GetMapping("/roles")
    @RequirePerm("role:list")
    public Result<List<SysRole>> roles() {
        return Result.ok(roleMapper.selectList(
                Wrappers.<SysRole>lambdaQuery().orderByAsc(SysRole::getId)));
    }

    /** 全部权限码，按模块分组 */
    @GetMapping("/permissions")
    @RequirePerm("role:list")
    public Result<Map<String, List<SysPermission>>> permissions() {
        return Result.ok(permissionService.listGroupedByModule());
    }

    /** 某用户当前生效的权限码（角色权限 ∪ 追加权限） */
    @GetMapping("/users/{userId}/permissions")
    @RequirePerm("user:grant")
    public Result<List<String>> userPermissions(@PathVariable Long userId) {
        return Result.ok(permissionService.load(userId).permCodes().stream().sorted().toList());
    }

    /** 某用户被单独追加的权限码（不含角色自带） */
    @GetMapping("/users/{userId}/extra-permissions")
    @RequirePerm("user:grant")
    public Result<List<String>> userExtraPermissions(@PathVariable Long userId) {
        List<Long> permIds = userPermMapper.selectList(
                        Wrappers.<SysUserPermission>lambdaQuery().eq(SysUserPermission::getUserId, userId))
                .stream().map(SysUserPermission::getPermId).toList();
        if (permIds.isEmpty()) {
            return Result.ok(List.of());
        }
        return Result.ok(permissionMapper.selectBatchIds(permIds).stream()
                .map(SysPermission::getCode).sorted().toList());
    }

    /**
     * 给用户追加一个权限（只加不减的语义）。
     * <p>
     * 典型用途：学生干部要发问卷 → 追加 {@code survey:create}。
     * 注意这是"额外授权"，不会被角色变更覆盖。
     */
    @PostMapping("/users/{userId}/permissions")
    @RequirePerm("user:grant")
    @Transactional(rollbackFor = Exception.class)
    public Result<Void> grant(@PathVariable Long userId,
                              @RequestParam String permCode,
                              HttpServletRequest httpReq) {
        SysPermission perm = permissionMapper.selectOne(
                Wrappers.<SysPermission>lambdaQuery().eq(SysPermission::getCode, permCode));
        if (perm == null) {
            throw BizException.badRequest("权限码不存在：" + permCode);
        }
        Long exists = userPermMapper.selectCount(Wrappers.<SysUserPermission>lambdaQuery()
                .eq(SysUserPermission::getUserId, userId)
                .eq(SysUserPermission::getPermId, perm.getId()));
        if (exists != null && exists > 0) {
            throw BizException.conflict("该用户已拥有此追加权限");
        }

        SysUserPermission up = new SysUserPermission();
        up.setUserId(userId);
        up.setPermId(perm.getId());
        up.setGrantTime(LocalDateTime.now());
        userPermMapper.insert(up);
        permissionService.evict(userId);

        operationLogService.write("权限管理", "追加权限", "USER", userId, "perm=" + permCode, httpReq);
        return Result.okMsg("权限已追加");
    }

    /** 撤销一条追加权限（角色自带的权限不受影响） */
    @DeleteMapping("/users/{userId}/permissions")
    @RequirePerm("user:grant")
    @Transactional(rollbackFor = Exception.class)
    public Result<Void> revoke(@PathVariable Long userId,
                               @RequestParam String permCode,
                               HttpServletRequest httpReq) {
        SysPermission perm = permissionMapper.selectOne(
                Wrappers.<SysPermission>lambdaQuery().eq(SysPermission::getCode, permCode));
        if (perm == null) {
            throw BizException.badRequest("权限码不存在：" + permCode);
        }
        int rows = userPermMapper.delete(Wrappers.<SysUserPermission>lambdaQuery()
                .eq(SysUserPermission::getUserId, userId)
                .eq(SysUserPermission::getPermId, perm.getId()));
        if (rows == 0) {
            throw BizException.notFound("该用户没有这条追加权限");
        }
        permissionService.evict(userId);

        operationLogService.write("权限管理", "撤销权限", "USER", userId, "perm=" + permCode, httpReq);
        return Result.okMsg("权限已撤销");
    }
}
