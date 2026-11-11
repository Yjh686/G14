package com.yjh.backend1.module.config.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import com.yjh.backend1.common.Result;
import com.yjh.backend1.module.config.dto.ConfigSaveRequest;
import com.yjh.backend1.module.config.dto.ConfigVO;
import com.yjh.backend1.module.config.service.ConfigService;
import com.yjh.backend1.security.RequirePerm;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 系统配置接口（FR-CFG-01/02/03）。
 * <p>
 * 三个层次：
 * <ul>
 *   <li>{@code /api/config/public} —— <b>完全公开</b>，登录页要拿站点名和密码规则，
 *       此时用户还没有令牌。它只返回 is_public=1 的项，默认密码这类私有项读不到。</li>
 *   <li>{@code /api/config} 系列 —— 需要 {@code config:manage}，全量读写。</li>
 *   <li>任何登录用户都能通过 {@code /api/auth/me} 间接看到密码规则文案，不额外开口子。</li>
 * </ul>
 */
@Tag(name = "10 系统配置")
@RestController
@RequestMapping("/api/config")
@RequiredArgsConstructor
@Validated
public class ConfigController {

    private final ConfigService configService;

    /**
     * 公开配置：未登录可读。
     * <p>
     * 注意这里<b>不能</b>加 @RequirePerm —— 登录页在用户还没登录时就要渲染站点名，
     * 加了注解等于把登录页的标题变成"请先登录"。
     */
    @GetMapping("/public")
    public Result<Map<String, String>> publicConfig() {
        return Result.ok(configService.publicMap());
    }

    /** 全部配置，按分组归拢（管理员） */
    @GetMapping
    @RequirePerm("config:manage")
    public Result<Map<String, List<ConfigVO>>> list() {
        return Result.ok(configService.listGrouped());
    }

    /** 批量保存（管理员） */
    @PutMapping
    @RequirePerm("config:manage")
    public Result<Integer> save(@RequestBody List<ConfigSaveRequest> items, HttpServletRequest httpReq) {
        return Result.ok(configService.saveBatch(items, httpReq), "配置已保存");
    }

    /** 新增自定义配置项（管理员） */
    @PostMapping
    @RequirePerm("config:manage")
    public Result<ConfigVO> create(@Valid @RequestBody ConfigSaveRequest req, HttpServletRequest httpReq) {
        return Result.ok(configService.create(req, httpReq), "配置项已创建");
    }

    /** 删除自定义配置项（管理员，内置项不可删） */
    @DeleteMapping("/{id}")
    @RequirePerm("config:manage")
    public Result<Void> delete(@PathVariable Long id, HttpServletRequest httpReq) {
        configService.delete(id, httpReq);
        return Result.ok(null, "配置项已删除");
    }
}
