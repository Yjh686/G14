package com.yjh.backend1.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI 文档配置。
 *
 * <p>D-06 要求「接口文档 + Swagger UI」。文档正文在 {@code docs/api/} 下按模块分 6 份，
 * 这里提供的是可交互的运行时版本：打开 {@code /swagger-ui.html} 就能看到 72 个真实接口、
 * 参数与 DTO 结构，并且带「Authorize」按钮填入 JWT 后可直接调。
 *
 * <p>为什么只加 {@code springdoc} 而不在 72 个方法上堆 {@code @Operation} 注解：
 * 方法名与中文 {@code Result.ok(...)} 消息已经表达了意图，逐个补注解的收益远小于维护成本；
 * 真正需要人工解释的口径（统计分母、匿名裁剪、快照语义）都写在 {@code docs/api/*.md} 里。
 *
 * <p>安全方案刻意声明为 {@code bearerAuth}：令牌放在 {@code Authorization: Bearer xxx}，
 * 与 {@code AuthInterceptor} 的解析方式一致。Swagger UI 上点 Authorize 之后，
 * 后续「Try it out」会自动带上这个头，省得每次手工粘贴。
 */
@Configuration
public class OpenApiConfig {

    private static final String SECURITY_SCHEME = "bearerAuth";

    @Bean
    public OpenAPI campusSurveyOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("校园问卷信息调查管理系统 API")
                        .version("1.0.0")
                        .description("""
                                三角色：系统管理员（ROLE_ADMIN）、教师（ROLE_TEACHER）、学生（ROLE_STUDENT）。

                                **权限模型**：每个接口由 `@RequirePerm` 校验权限码，数据归属另由 Service 层校验
                                （教师只能操作自己的问卷，越权返回 403「无权操作他人的问卷」）。

                                **响应约定**：`{code, msg, data, timestamp}`，`code` 与 HTTP 状态码一致。
                                唯一例外是 3 个导出接口（答卷 / 登录日志 / 操作日志），
                                它们直接写二进制流，不包统一响应体。

                                **登录**：`POST /api/auth/login` 拿 token，点右上角 Authorize 填入即可。
                                演示账号 admin/Admin@123、teacher/Teacher@123、student/Student@123。
                                """)
                        .contact(new Contact().name("G14 课程项目")))
                .components(new Components().addSecuritySchemes(SECURITY_SCHEME,
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("登录接口返回的 token，直接粘贴，不需要自己加 Bearer 前缀")))
                .addSecurityItem(new SecurityRequirement().addList(SECURITY_SCHEME));
    }
}
