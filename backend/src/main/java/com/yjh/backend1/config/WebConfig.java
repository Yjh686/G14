package com.yjh.backend1.config;

import com.yjh.backend1.security.AuthInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import jakarta.servlet.http.HttpServletRequest;

import java.util.ArrayList;
import java.util.List;

/**
 * Web 配置：拦截器注册、CORS、密码编码器。
 */
@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns(
                        "/api/auth/login",
                        "/api/auth/register",
                        "/api/health"
                );
    }

    /**
     * BCrypt（cost=10，约 50~80ms/次）。
     * 刻意不用 MD5/SHA：校园项目也应当存不可逆哈希。
     */
    @Bean
    public BCryptPasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(10);
    }

    /**
     * 跨域配置。
     *
     * <p>历史：这里原本是写死的白名单 {@code http://localhost:*} / {@code http://127.0.0.1:*}，
     * 用来配合 vite dev server（5173）。上线后暴露出一个很隐蔽的问题：
     * <b>浏览器的同源 POST 也会带 Origin 头</b>，而白名单里没有正式域名，
     * 于是每个写操作都被判 {@code Invalid CORS request} 返回 403 ——
     * 页面能打开、能加载资源，只有登录和保存失败，看起来完全不像跨域问题。
     * （用 curl / Postman 测不出来，因为它们默认不发 Origin。）
     *
     * <p>所以现在改成「按次判定」：
     * <ol>
     *   <li>先算本次请求自己的源（scheme + Host），取自 {@code X-Forwarded-Proto} 与 {@code Host}，
     *       这样经 nginx、Cloudflare 隧道、内网 IP 访问都算得对；</li>
     *   <li>Origin 与自己的源一致 → 这是同源请求，放行；</li>
     *   <li>否则回落到本机开发地址白名单（vite dev server）；</li>
     *   <li>都对不上 → 仍然拒绝，不做「允许所有来源」那种一刀切。</li>
     * </ol>
     *
     * <p>为什么不直接 {@code allowedOriginPatterns("*")}：那等于把接口开放给任意网站，
     * 一旦将来引入 Cookie 会话就是 CSRF 缺口。这里放行的是「请求自己」，
     * 既不依赖部署域名，也不扩大攻击面。
     */
    @Bean
    public CorsFilter corsFilter() {
        return new CorsFilter(request -> {
            CorsConfiguration cfg = new CorsConfiguration();
            List<String> patterns = new ArrayList<>(List.of("http://localhost:*", "http://127.0.0.1:*"));

            String origin = request.getHeader("Origin");
            if (origin != null && !origin.isBlank() && origin.equalsIgnoreCase(selfOrigin(request))) {
                patterns.add(origin);
            }

            cfg.setAllowedOriginPatterns(patterns);
            cfg.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"));
            cfg.setAllowedHeaders(List.of("*"));
            cfg.setExposedHeaders(List.of("Content-Disposition"));
            cfg.setAllowCredentials(true);
            cfg.setMaxAge(3600L);
            return cfg;
        });
    }

    /**
     * 计算本次请求自身的源（形如 {@code https://xxx.trycloudflare.com}）。
     * 部署形态有三种，scheme 必须分开取，否则算出来的源永远对不上 Origin：
     * 直连 Tomcat 用 {@code getScheme()}；经 nginx / 隧道用 {@code X-Forwarded-Proto}。
     * 端口只在非默认端口时拼上，浏览器发 Origin 时也是这个规则。
     */
    private String selfOrigin(HttpServletRequest request) {
        String proto = request.getHeader("X-Forwarded-Proto");
        String scheme = (proto == null || proto.isBlank()) ? request.getScheme() : proto.split(",")[0].trim();

        String host = request.getHeader("X-Forwarded-Host");
        if (host == null || host.isBlank()) {
            host = request.getHeader("Host");
        }
        if (host == null || host.isBlank()) {
            host = request.getServerName();
            int port = request.getServerPort();
            boolean dflt = ("http".equals(scheme) && port == 80) || ("https".equals(scheme) && port == 443);
            if (!dflt) {
                host = host + ":" + port;
            }
        }
        return scheme + "://" + host.trim();
    }
}
