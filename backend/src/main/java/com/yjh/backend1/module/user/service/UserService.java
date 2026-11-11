package com.yjh.backend1.module.user.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yjh.backend1.common.BizException;
import com.yjh.backend1.common.PageResult;
import com.yjh.backend1.common.PasswordPolicy;
import com.yjh.backend1.module.config.service.ConfigService;
import com.yjh.backend1.module.log.service.OperationLogService;
import com.yjh.backend1.module.rbac.entity.SysRole;
import com.yjh.backend1.module.rbac.entity.SysUserRole;
import com.yjh.backend1.module.rbac.mapper.SysRoleMapper;
import com.yjh.backend1.module.rbac.mapper.SysUserRoleMapper;
import com.yjh.backend1.module.rbac.service.PermissionService;
import com.yjh.backend1.module.user.dto.AuditUserRequest;
import com.yjh.backend1.module.user.dto.CreateUserRequest;
import com.yjh.backend1.module.user.dto.UpdateUserRequest;
import com.yjh.backend1.module.user.dto.UserQuery;
import com.yjh.backend1.module.user.dto.UserVO;
import com.yjh.backend1.module.user.entity.SysUser;
import com.yjh.backend1.module.user.mapper.SysUserMapper;
import com.yjh.backend1.security.LoginUser;
import com.yjh.backend1.security.UserContext;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 用户管理（ADMIN）。
 * <p>
 * 几条硬约束：
 * <ul>
 *   <li>不能删除/禁用自己 —— 避免管理员把自己锁在门外</li>
 *   <li>不能删除最后一个启用的管理员 —— 否则系统再没人能进后台</li>
 *   <li>重置密码后 {@code token_version+1}，把该用户所有已登录设备踢下线</li>
 *   <li>账号（username）不可改，改了对不上历史答卷归属</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    private final SysUserMapper userMapper;
    private final SysRoleMapper roleMapper;
    private final SysUserRoleMapper userRoleMapper;
    private final PermissionService permissionService;
    private final OperationLogService operationLogService;
    private final BCryptPasswordEncoder passwordEncoder;
    /** 默认密码与密码规则改由系统配置维护（FR-CFG-02），管理员改完即时生效 */
    private final ConfigService configService;

    // =========================================================
    // 查询
    // =========================================================

    public PageResult<UserVO> page(UserQuery q) {
        int page = q.getPage() == null || q.getPage() < 1 ? 1 : q.getPage();
        int size = q.getSize() == null || q.getSize() < 1 ? 20 : Math.min(q.getSize(), 100);

        LambdaQueryWrapper<SysUser> w = Wrappers.lambdaQuery();
        if (q.getKeyword() != null && !q.getKeyword().isBlank()) {
            String kw = q.getKeyword().trim();
            w.and(x -> x.like(SysUser::getUsername, kw).or().like(SysUser::getRealName, kw));
        }
        if (q.getStatus() != null) {
            w.eq(SysUser::getStatus, q.getStatus());
        }
        if (q.getAuditStatus() != null) {
            w.eq(SysUser::getAuditStatus, q.getAuditStatus());
        }
        // 按角色过滤：先查该角色下的 userId 集合。用户量 < 500，两步查询比联表更省心
        if (q.getRoleCode() != null && !q.getRoleCode().isBlank()) {
            SysRole role = roleMapper.selectOne(
                    Wrappers.<SysRole>lambdaQuery().eq(SysRole::getCode, q.getRoleCode()));
            if (role == null) {
                return PageResult.empty(page, size);
            }
            List<Long> ids = userRoleMapper.selectList(
                            Wrappers.<SysUserRole>lambdaQuery().eq(SysUserRole::getRoleId, role.getId()))
                    .stream().map(SysUserRole::getUserId).toList();
            if (ids.isEmpty()) {
                return PageResult.empty(page, size);
            }
            w.in(SysUser::getId, ids);
        }
        w.orderByDesc(SysUser::getId);

        Page<SysUser> p = userMapper.selectPage(new Page<>(page, size), w);
        List<UserVO> vos = toVOList(p.getRecords());
        return PageResult.of(vos, p.getTotal(), p.getCurrent(), p.getSize());
    }

    public UserVO detail(Long id) {
        SysUser u = userMapper.selectById(id);
        if (u == null) {
            throw BizException.notFound("用户不存在");
        }
        return toVOList(List.of(u)).get(0);
    }

    // =========================================================
    // 新建 / 修改 / 删除
    // =========================================================

    @Transactional(rollbackFor = Exception.class)
    public UserVO create(CreateUserRequest req, HttpServletRequest httpReq) {
        String username = req.getUsername().trim();
        Long exists = userMapper.selectCount(
                Wrappers.<SysUser>lambdaQuery().eq(SysUser::getUsername, username));
        if (exists != null && exists > 0) {
            throw BizException.conflict("该学号/工号已存在");
        }

        boolean useDefault = req.getPassword() == null || req.getPassword().isBlank();
        String rawPwd = useDefault ? configService.defaultPassword() : req.getPassword();
        if (!useDefault) {
            PasswordPolicy.check(rawPwd, configService.passwordRules());
        }

        SysRole role = requireRole(req.getRoleCode());

        SysUser u = new SysUser();
        u.setUsername(username);
        u.setPasswordHash(passwordEncoder.encode(rawPwd));
        u.setRealName(req.getRealName().trim());
        u.setStatus(1);
        u.setAuditStatus(1);                      // ADMIN 直接创建 = 已审核
        u.setMustChangePassword(useDefault ? 1 : 0); // 用默认密码必须首次改密
        u.setDepartment(blankToNull(req.getDepartment()));
        u.setPhone(blankToNull(req.getPhone()));
        u.setEmail(blankToNull(req.getEmail()));
        u.setRemark(blankToNull(req.getRemark()));
        u.setTokenVersion(0);
        u.setLoginFailCount(0);
        userMapper.insert(u);

        SysUserRole ur = new SysUserRole();
        ur.setUserId(u.getId());
        ur.setRoleId(role.getId());
        userRoleMapper.insert(ur);

        operationLogService.write("用户管理", "新建用户", "USER", u.getId(),
                "username=" + username + " role=" + role.getCode(), httpReq);

        return detail(u.getId());
    }

    @Transactional(rollbackFor = Exception.class)
    public UserVO update(Long id, UpdateUserRequest req, HttpServletRequest httpReq) {
        SysUser old = userMapper.selectById(id);
        if (old == null) {
            throw BizException.notFound("用户不存在");
        }

        SysUser upd = new SysUser();
        upd.setId(id);
        if (req.getRealName() != null && !req.getRealName().isBlank()) {
            upd.setRealName(req.getRealName().trim());
        }
        if (req.getDepartment() != null) {
            upd.setDepartment(blankToNull(req.getDepartment()));
        }
        if (req.getPhone() != null) {
            String phone = req.getPhone().trim();
            if (!phone.isEmpty()) {
                Long cnt = userMapper.selectCount(Wrappers.<SysUser>lambdaQuery()
                        .eq(SysUser::getPhone, phone).ne(SysUser::getId, id));
                if (cnt != null && cnt > 0) {
                    throw BizException.conflict("该手机号已被其他账号使用");
                }
            }
            upd.setPhone(phone.isEmpty() ? null : phone);
        }
        if (req.getEmail() != null) {
            upd.setEmail(blankToNull(req.getEmail()));
        }
        if (req.getRemark() != null) {
            upd.setRemark(blankToNull(req.getRemark()));
        }
        userMapper.updateById(upd);

        // 角色变更：一人一角色，先删后插
        if (req.getRoleCode() != null && !req.getRoleCode().isBlank()) {
            SysRole newRole = requireRole(req.getRoleCode());
            List<SysUserRole> current = userRoleMapper.selectList(
                    Wrappers.<SysUserRole>lambdaQuery().eq(SysUserRole::getUserId, id));
            boolean same = current.size() == 1 && current.get(0).getRoleId().equals(newRole.getId());
            if (!same) {
                if (id.equals(UserContext.requireUserId())) {
                    throw BizException.badRequest("不能修改自己的角色，请让其他管理员操作");
                }
                if (isAdmin(id) && !isAnotherAdminExists(id)) {
                    throw BizException.conflict("系统必须保留至少一名管理员，无法变更最后一名管理员的角色");
                }
                userRoleMapper.delete(Wrappers.<SysUserRole>lambdaQuery().eq(SysUserRole::getUserId, id));
                SysUserRole ur = new SysUserRole();
                ur.setUserId(id);
                ur.setRoleId(newRole.getId());
                userRoleMapper.insert(ur);
                permissionService.evict(id);
                operationLogService.write("用户管理", "变更角色", "USER", id,
                        "username=" + old.getUsername() + " -> " + newRole.getCode(), httpReq);
            }
        }

        operationLogService.write("用户管理", "修改用户", "USER", id, "username=" + old.getUsername(), httpReq);
        return detail(id);
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id, HttpServletRequest httpReq) {
        SysUser u = userMapper.selectById(id);
        if (u == null) {
            throw BizException.notFound("用户不存在");
        }
        if (id.equals(UserContext.requireUserId())) {
            throw BizException.badRequest("不能删除自己的账号");
        }
        if (isAdmin(id) && !isAnotherAdminExists(id)) {
            throw BizException.conflict("系统必须保留至少一名管理员，无法删除最后一名管理员");
        }

        // 逻辑删除；同时 version+1 让该用户已签发的令牌立即失效
        SysUser upd = new SysUser();
        upd.setId(id);
        upd.setTokenVersion((u.getTokenVersion() == null ? 0 : u.getTokenVersion()) + 1);
        userMapper.updateById(upd);
        userMapper.deleteById(id);
        permissionService.evict(id);

        operationLogService.write("用户管理", "删除用户", "USER", id, "username=" + u.getUsername(), httpReq);
    }

    // =========================================================
    // 状态 / 审核 / 重置密码
    // =========================================================

    @Transactional(rollbackFor = Exception.class)
    public void changeStatus(Long id, Integer status, HttpServletRequest httpReq) {
        if (status == null || (status != 0 && status != 1)) {
            throw BizException.badRequest("状态值只能是 0 或 1");
        }
        SysUser u = userMapper.selectById(id);
        if (u == null) {
            throw BizException.notFound("用户不存在");
        }
        if (status == 0) {
            if (id.equals(UserContext.requireUserId())) {
                throw BizException.badRequest("不能禁用自己的账号");
            }
            if (isAdmin(id) && !isAnotherAdminExists(id)) {
                throw BizException.conflict("系统必须保留至少一名启用状态的管理员");
            }
        }

        SysUser upd = new SysUser();
        upd.setId(id);
        upd.setStatus(status);
        if (status == 0) {
            // 禁用立即生效：令牌版本 +1，已登录设备下一次请求就会被踢出
            upd.setTokenVersion((u.getTokenVersion() == null ? 0 : u.getTokenVersion()) + 1);
        }
        userMapper.updateById(upd);
        permissionService.evict(id);

        operationLogService.write("用户管理", status == 1 ? "启用用户" : "禁用用户", "USER", id,
                "username=" + u.getUsername(), httpReq);
    }

    @Transactional(rollbackFor = Exception.class)
    public void audit(Long id, AuditUserRequest req, HttpServletRequest httpReq) {
        Integer status = req.getAuditStatus();
        if (status == null || (status != 1 && status != 2)) {
            throw BizException.badRequest("审核结果只能是 1（通过）或 2（驳回）");
        }
        if (status == 2 && (req.getAuditRemark() == null || req.getAuditRemark().isBlank())) {
            throw BizException.badRequest("驳回时必须填写审核意见");
        }
        SysUser u = userMapper.selectById(id);
        if (u == null) {
            throw BizException.notFound("用户不存在");
        }

        SysUser upd = new SysUser();
        upd.setId(id);
        upd.setAuditStatus(status);
        upd.setAuditRemark(blankToNull(req.getAuditRemark()));
        userMapper.updateById(upd);
        permissionService.evict(id);

        operationLogService.write("用户管理", status == 1 ? "审核通过" : "审核驳回", "USER", id,
                "username=" + u.getUsername() + " remark=" + req.getAuditRemark(), httpReq);
    }

    /**
     * 重置密码为系统默认密码，并要求该用户下次登录必须改密。
     * token_version+1 会把该用户所有在线设备踢下线。
     */
    @Transactional(rollbackFor = Exception.class)
    public String resetPassword(Long id, HttpServletRequest httpReq) {
        SysUser u = userMapper.selectById(id);
        if (u == null) {
            throw BizException.notFound("用户不存在");
        }
        SysUser upd = new SysUser();
        upd.setId(id);
        String defaultPwd = configService.defaultPassword();
        upd.setPasswordHash(passwordEncoder.encode(defaultPwd));
        upd.setMustChangePassword(1);
        upd.setTokenVersion((u.getTokenVersion() == null ? 0 : u.getTokenVersion()) + 1);
        upd.setLoginFailCount(0);
        upd.setLockedUntil(null);
        userMapper.updateById(upd);
        permissionService.evict(id);

        operationLogService.write("用户管理", "重置密码", "USER", id, "username=" + u.getUsername(), httpReq);
        return defaultPwd;
    }

    // =========================================================
    // 内部
    // =========================================================

    private SysRole requireRole(String code) {
        SysRole role = roleMapper.selectOne(Wrappers.<SysRole>lambdaQuery().eq(SysRole::getCode, code));
        if (role == null) {
            throw BizException.badRequest("角色不存在：" + code);
        }
        return role;
    }

    private boolean isAdmin(Long userId) {
        List<SysUserRole> urs = userRoleMapper.selectList(
                Wrappers.<SysUserRole>lambdaQuery().eq(SysUserRole::getUserId, userId));
        if (urs.isEmpty()) {
            return false;
        }
        SysRole r = roleMapper.selectById(urs.get(0).getRoleId());
        return r != null && SysRole.ADMIN.equals(r.getCode());
    }

    /** 除 excludeUserId 外，是否还存在至少一名启用状态的 ADMIN */
    private boolean isAnotherAdminExists(Long excludeUserId) {
        SysRole adminRole = roleMapper.selectOne(
                Wrappers.<SysRole>lambdaQuery().eq(SysRole::getCode, SysRole.ADMIN));
        if (adminRole == null) {
            return false;
        }
        List<Long> adminIds = userRoleMapper.selectList(Wrappers.<SysUserRole>lambdaQuery()
                        .eq(SysUserRole::getRoleId, adminRole.getId()))
                .stream().map(SysUserRole::getUserId)
                .filter(uid -> !uid.equals(excludeUserId))
                .toList();
        if (adminIds.isEmpty()) {
            return false;
        }
        Long cnt = userMapper.selectCount(Wrappers.<SysUser>lambdaQuery()
                .in(SysUser::getId, adminIds)
                .eq(SysUser::getStatus, 1));
        return cnt != null && cnt > 0;
    }

    private List<UserVO> toVOList(List<SysUser> users) {
        if (users == null || users.isEmpty()) {
            return Collections.emptyList();
        }
        List<Long> ids = users.stream().map(SysUser::getId).toList();

        // 一次查出这批用户的角色，避免 N+1
        List<SysUserRole> urs = userRoleMapper.selectList(
                Wrappers.<SysUserRole>lambdaQuery().in(SysUserRole::getUserId, ids));
        Map<Long, Long> userRoleMap = new HashMap<>();
        for (SysUserRole ur : urs) {
            userRoleMap.putIfAbsent(ur.getUserId(), ur.getRoleId());
        }
        Map<Long, SysRole> roleMap = new HashMap<>();
        if (!userRoleMap.isEmpty()) {
            Set<Long> roleIds = new HashSet<>(userRoleMap.values());
            for (SysRole r : roleMapper.selectBatchIds(roleIds)) {
                roleMap.put(r.getId(), r);
            }
        }

        List<UserVO> out = new ArrayList<>(users.size());
        for (SysUser u : users) {
            UserVO v = new UserVO();
            v.setId(u.getId());
            v.setUsername(u.getUsername());
            v.setRealName(u.getRealName());
            v.setStatus(u.getStatus());
            v.setAuditStatus(u.getAuditStatus());
            v.setAuditRemark(u.getAuditRemark());
            v.setMustChangePassword(u.getMustChangePassword());
            v.setPhone(u.getPhone());
            v.setEmail(u.getEmail());
            v.setDepartment(u.getDepartment());
            v.setRemark(u.getRemark());
            v.setLastLoginTime(u.getLastLoginTime());
            v.setCreateTime(u.getCreateTime());
            v.setUpdateTime(u.getUpdateTime());
            Long rid = userRoleMap.get(u.getId());
            SysRole role = rid == null ? null : roleMap.get(rid);
            if (role != null) {
                v.setRoleCode(role.getCode());
                v.setRoleName(role.getName());
            }
            out.add(v);
        }
        return out;
    }

    private static String blankToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
