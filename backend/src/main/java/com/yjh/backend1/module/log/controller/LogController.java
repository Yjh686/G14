package com.yjh.backend1.module.log.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import com.yjh.backend1.common.PageResult;
import com.yjh.backend1.common.Result;
import com.yjh.backend1.module.log.dto.LogQuery;
import com.yjh.backend1.module.log.dto.LoginLogVO;
import com.yjh.backend1.module.log.dto.OperationLogVO;
import com.yjh.backend1.module.log.service.LogService;
import com.yjh.backend1.security.RequirePerm;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.List;

/**
 * 日志查询接口（FR-LOG-01/02/03）。
 * <p>
 * <b>只有 GET：</b>没有 PUT/DELETE 是设计而非遗漏 —— FR-LOG-03 要求日志只读，
 * 管理员也不能改。要「清理」只能由 DBA 直接操作数据库，这样任何删改都留得下痕迹。
 */
@Tag(name = "09 日志审计")
@RestController
@RequestMapping("/api/logs")
@RequiredArgsConstructor
public class LogController {

    private final LogService logService;

    /** 登录日志：含 IP 与结果（FR-LOG-01） */
    @GetMapping("/login")
    @RequirePerm("log:login:read")
    public Result<PageResult<LoginLogVO>> loginLogs(LogQuery query) {
        return Result.ok(logService.pageLoginLogs(query));
    }

    /** 操作日志：覆盖发布/删除/导出/权限变更等（FR-LOG-02） */
    @GetMapping("/operations")
    @RequirePerm("log:operation:read")
    public Result<PageResult<OperationLogVO>> operationLogs(LogQuery query) {
        return Result.ok(logService.pageOperationLogs(query));
    }

    /** 操作日志里出现过的模块名，用于前端筛选下拉 */
    @GetMapping("/modules")
    @RequirePerm("log:operation:read")
    public Result<List<String>> modules() {
        return Result.ok(logService.distinctModules());
    }

    /** 导出登录日志（流式 xlsx，与答卷导出同为非统一响应结构） */
    @GetMapping("/login/export")
    @RequirePerm("log:export")
    public void exportLoginLogs(LogQuery query, HttpServletResponse response, HttpServletRequest request)
            throws IOException {
        logService.exportLoginLogs(query, response, request);
    }

    /** 导出操作日志 */
    @GetMapping("/operations/export")
    @RequirePerm("log:export")
    public void exportOperationLogs(LogQuery query, HttpServletResponse response, HttpServletRequest request)
            throws IOException {
        logService.exportOperationLogs(query, response, request);
    }
}
