package com.g14.g14.login.config;

import com.g14.g14.login.security.AuthInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web 层配置：注册鉴权拦截器 + 跨域策略。
 *
 * <p>{@code @EnableScheduling} 用于开启 {@code TokenBlacklist} 的过期条目清理任务。
 */
@Configuration
@EnableScheduling
public class WebMvcConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;

    public WebMvcConfig(AuthInterceptor authInterceptor) {
        this.authInterceptor = authInterceptor;
    }

    /**
     * 鉴权中间件注册。
     *
     * <p>采用「拦截 {@code /api/**} 全部接口 + 用 {@code @PublicApi} 显式放行」的白名单模式。
     * 相比维护 excludePathPatterns 黑名单，这种写法在新增接口时默认是安全的。
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/api/**")
                .order(0);
    }

    /**
     * 跨域配置。前端为独立部署的静态站点（默认 http://localhost:5173），
     * 鉴权依赖 Authorization 请求头而非 Cookie，因此无需 allowCredentials。
     */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS")
                .allowedHeaders("*")
                .exposedHeaders("Authorization")
                .allowCredentials(false)
                .maxAge(3600);
    }
}
