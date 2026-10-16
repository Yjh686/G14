package com.g14.g14.login.entity;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 班级实体，对应表 class_info。
 *
 * <p>班级模块本体尚未交付，本表由权限演示端点使用，
 * 用于验证「老师只能操作自己创建的班级」这一数据级权限规则。
 */
@Data
public class ClassInfo implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;
    private String name;
    private String inviteCode;
    /** 创建老师 sys_user.id */
    private Long teacherId;
    private LocalDateTime createdAt;
}
