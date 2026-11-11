package com.yjh.backend1.module.rbac.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yjh.backend1.module.rbac.entity.SysPermission;
import com.yjh.backend1.module.rbac.entity.SysRole;
import com.yjh.backend1.module.rbac.entity.SysRolePermission;
import com.yjh.backend1.module.rbac.entity.SysUserPermission;
import com.yjh.backend1.module.rbac.entity.SysUserRole;
import com.yjh.backend1.module.rbac.mapper.SysPermissionMapper;
import com.yjh.backend1.module.rbac.mapper.SysRoleMapper;
import com.yjh.backend1.module.rbac.mapper.SysRolePermissionMapper;
import com.yjh.backend1.module.rbac.mapper.SysUserPermissionMapper;
import com.yjh.backend1.module.rbac.mapper.SysUserRoleMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 权限装配服务：把「角色权限 ∪ 用户追加权限」算成一个扁平权限码集合。
 * <p>
 * 性能考虑：每个请求都会用到，所以做本地缓存（TTL 由 {@code app.security.perm-cache-seconds} 控制）。
 * 任何改变权限的操作（改角色、追加权限、禁用用户）都必须主动调用 {@link #evict(Long)}，
 * TTL 只是兜底，不能依赖它来保证实时性。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PermissionService {

    private final SysUserRoleMapper userRoleMapper;
    private final SysRoleMapper roleMapper;
    private final SysRolePermissionMapper rolePermMapper;
    private final SysUserPermissionMapper userPermMapper;
    private final SysPermissionMapper permissionMapper;

    @Value("${app.security.perm-cache-seconds:60}")
    private long cacheSeconds;

    /** userId -> 缓存项 */
    private final Map<Long, CacheEntry> cache = new ConcurrentHashMap<>();

    private record CacheEntry(Set<String> permCodes, String roleCode, String roleName, long expireAt) {
        boolean expired() {
            return System.currentTimeMillis() > expireAt;
        }
    }

    /** 用户权限快照 */
    public record Snapshot(Set<String> permCodes, String roleCode, String roleName) {
    }

    /**
     * 获取用户权限快照。用户无角色时返回空权限 + null 角色。
     */
    public Snapshot load(Long userId) {
        CacheEntry e = cache.get(userId);
        if (e != null && !e.expired()) {
            return new Snapshot(e.permCodes(), e.roleCode(), e.roleName());
        }
        Snapshot s = queryFromDb(userId);
        cache.put(userId, new CacheEntry(
                s.permCodes(), s.roleCode(), s.roleName(),
                System.currentTimeMillis() + cacheSeconds * 1000));
        return s;
    }

    private Snapshot queryFromDb(Long userId) {
        // 本期一人一角色：取第一条即可；表结构已支持多角色，后续改 list 也不用动调用方
        List<SysUserRole> urs = userRoleMapper.selectList(
                Wrappers.<SysUserRole>lambdaQuery().eq(SysUserRole::getUserId, userId));
        if (urs.isEmpty()) {
            return new Snapshot(Collections.emptySet(), null, null);
        }
        Long roleId = urs.get(0).getRoleId();
        SysRole role = roleMapper.selectById(roleId);

        Set<String> codes = new HashSet<>();

        // 1) 角色权限
        List<SysRolePermission> rps = rolePermMapper.selectList(
                Wrappers.<SysRolePermission>lambdaQuery().eq(SysRolePermission::getRoleId, roleId));
        if (!rps.isEmpty()) {
            List<Long> permIds = rps.stream().map(SysRolePermission::getPermId).distinct().toList();
            codes.addAll(permissionMapper.selectBatchIds(permIds).stream()
                    .map(SysPermission::getCode).collect(Collectors.toSet()));
        }

        // 2) 用户追加权限（只增不减）
        List<SysUserPermission> ups = userPermMapper.selectList(
                Wrappers.<SysUserPermission>lambdaQuery().eq(SysUserPermission::getUserId, userId));
        if (!ups.isEmpty()) {
            List<Long> permIds = ups.stream().map(SysUserPermission::getPermId).distinct().toList();
            codes.addAll(permissionMapper.selectBatchIds(permIds).stream()
                    .map(SysPermission::getCode).collect(Collectors.toSet()));
        }

        return new Snapshot(codes, role == null ? null : role.getCode(), role == null ? null : role.getName());
    }

    /** 主动失效某个用户的权限缓存 */
    public void evict(Long userId) {
        if (userId != null) {
            cache.remove(userId);
        }
    }

    /** 清空全部缓存（角色权限变更时用） */
    public void evictAll() {
        cache.clear();
    }

    /** 查所有权限字典，按模块分组，供前端权限配置页使用 */
    public Map<String, List<SysPermission>> listGroupedByModule() {
        List<SysPermission> all = permissionMapper.selectList(
                Wrappers.<SysPermission>lambdaQuery().orderByAsc(SysPermission::getModule, SysPermission::getId));
        Map<String, List<SysPermission>> grouped = new HashMap<>();
        for (SysPermission p : all) {
            grouped.computeIfAbsent(p.getModule(), k -> new java.util.ArrayList<>()).add(p);
        }
        return grouped;
    }
}
