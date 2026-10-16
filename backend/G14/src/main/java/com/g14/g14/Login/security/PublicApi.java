package com.g14.g14.login.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 匿名接口标记：标注后 {@link AuthInterceptor} 不校验 Token。
 *
 * <p>采用「默认全部需要鉴权 + 显式声明匿名」的策略，
 * 避免新增接口时忘记加鉴权注解而导致越权（安全默认原则）。
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface PublicApi {
}
