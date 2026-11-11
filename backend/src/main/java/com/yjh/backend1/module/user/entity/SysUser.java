package com.yjh.backend1.module.user.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.yjh.backend1.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 用户实体，对应表 {@code sys_user}。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_user")
public class SysUser extends BaseEntity {

    /** 登录账号：学号 / 工号 / 管理员账号 */
    private String username;
    /** BCrypt 密码哈希 */
    private String passwordHash;
    /** 姓名 */
    private String realName;
    /** 状态：1=正常 0=禁用 */
    private Integer status;
    /** 审核状态：0=待审核 1=已通过 2=已驳回 */
    private Integer auditStatus;
    /** 是否需强制下次登录改密：1=是 0=否 */
    private Integer mustChangePassword;
    /** 手机号 */
    private String phone;
    /** 邮箱 */
    private String email;
    /** 院系 / 班级 */
    private String department;
    /** 令牌版本号：改密/重置/禁用时 +1，使旧 JWT 立即失效 */
    private Integer tokenVersion;
    /** 连续登录失败次数 */
    private Integer loginFailCount;
    /** 锁定截止时间，NULL 表示未锁定 */
    private LocalDateTime lockedUntil;
    /** 最近一次登录成功时间 */
    private LocalDateTime lastLoginTime;
    /** 审核意见 */
    private String auditRemark;
    /** 备注 */
    private String remark;

    /** 便捷判断：账号是否可用（未禁用、已通过审核、未删除） */
    public boolean isActive() {
        return Integer.valueOf(1).equals(status) && Integer.valueOf(1).equals(auditStatus);
    }
}
