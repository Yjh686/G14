package com.g14.g14.login.security;

import com.g14.g14.login.enums.RoleEnum;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 角色鉴权注解（RBAC 的「谁」这一维）。
 *
 * <p>可标注在类或方法上，方法级优先于类级：
 * <pre>
 * &#64;RequireRole(RoleEnum.ADMIN)                       // 仅管理员
 * &#64;RequireRole({RoleEnum.TEACHER, RoleEnum.STUDENT})  // 教师或学生
 * </pre>
 *
 * <p>注意：本注解只解决「角色维」的粗粒度控制。像「教师只能管理自己创建的班级」这类
 * <b>数据归属维</b>的细粒度控制，需在 Service 层基于 {@link AuthContext} 中的用户 ID 做归属校验，
 * 参见 {@code docs/权限设计.md} 中的 OwnershipChecker 说明。
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RequireRole {

    /** 允许访问的角色集合。 */
    RoleEnum[] value();

    /**
     * 是否允许管理员直接通过。
     * 默认 true，对应需求「管理员拥有全部权限」。
     */
    boolean allowAdminBypass() default true;
}
