package com.yjh.backend1.module.rbac.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 用户追加权限，对应表 {@code sys_user_permission}。
 * <p>
 * 用于「学生干部发卷」场景：默认角色不变，只给指定学生追加 {@code survey:create}。
 * 只做加权限，不做减权限，避免"权限被悄悄削掉"难以排查。
 */
@Data
@TableName("sys_user_permission")
public class SysUserPermission implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Long permId;
    private Long grantBy;
    private LocalDateTime grantTime;
    private String remark;
}
