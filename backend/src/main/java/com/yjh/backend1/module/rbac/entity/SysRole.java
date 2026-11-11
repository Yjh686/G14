package com.yjh.backend1.module.rbac.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 角色实体，对应表 {@code sys_role}。内置三角色，不可增删。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("sys_role")
public class SysRole implements Serializable {

    /** 角色码常量：系统管理员 */
    public static final String ADMIN = "ROLE_ADMIN";
    /** 角色码常量：教师 */
    public static final String TEACHER = "ROLE_TEACHER";
    /** 角色码常量：学生 */
    public static final String STUDENT = "ROLE_STUDENT";

    @TableId(type = IdType.AUTO)
    private Long id;
    private String code;
    private String name;
    private String description;
    private Integer builtin;
    private LocalDateTime createTime;
}
