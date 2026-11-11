package com.yjh.backend1.module.system.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import com.yjh.backend1.common.Result;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 健康检查。给前端启动探活、压测脚本预热用。
 */
@Tag(name = "11 健康检查")
@RestController
@RequestMapping("/api")
public class HealthController {

    @Value("${spring.application.name:backend}")
    private String appName;

    @GetMapping("/health")
    public Result<Map<String, Object>> health() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", "UP");
        m.put("app", appName);
        m.put("time", LocalDateTime.now());
        return Result.ok(m);
    }
}
