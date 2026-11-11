package com.yjh.backend1.security;

import com.yjh.backend1.common.BizException;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 接口权限声明。标注在 Controller 方法或类上。
 * <p>
 * 语义：
 * <ul>
 *   <li>标在方法上：需要该方法声明权限中的 <b>任意一个</b>（满足其一即可）</li>
 *   <li>{@code logic = AND}：需要全部满足</li>
 *   <li>{@code roles = {...}}：额外限定角色，与权限是 AND 关系</li>
 * </ul>
 * 例：
 * <pre>
 *   &#64;RequirePerm("user:list")                     // 有 user:list 即可
 *   &#64;RequirePerm(value = "survey:update:self",      // 自己的 OR 全部的
 *                or = "survey:update:all")
 *   &#64;RequirePerm(value = "template:audit", roles = "ROLE_ADMIN")
 * </pre>
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequirePerm {

    /** 权限码，多个表示"满足任意一个" */
    String[] value() default {};

    /** 额外的 OR 权限码，等价于把 value 和 or 合并成"任意其一" */
    String[] or() default {};

    /** 必须同时具备的角色码（AND 关系），空表示不限角色 */
    String[] roles() default {};

    /** 是否要求登录（默认 true）。设为 false 时仅做权限判断前的匿名放行检查 */
    boolean login() default true;
}
