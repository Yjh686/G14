package com.yjh.backend1.module.log.service;

import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.ExcelWriter;
import com.alibaba.excel.write.metadata.WriteSheet;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yjh.backend1.common.BizException;
import com.yjh.backend1.common.PageResult;
import com.yjh.backend1.module.log.dto.LogQuery;
import com.yjh.backend1.module.log.dto.LoginLogVO;
import com.yjh.backend1.module.log.dto.OperationLogVO;
import com.yjh.backend1.module.log.entity.SysLoginLog;
import com.yjh.backend1.module.log.entity.SysOperationLog;
import com.yjh.backend1.module.log.mapper.SysLoginLogMapper;
import com.yjh.backend1.module.log.mapper.SysOperationLogMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * 日志查询与导出（FR-LOG）。
 * <p>
 * <b>只读：</b>本 Service 没有任何 insert/update/delete 方法，对应 FR-LOG-03
 * 「日志只增不改，无编辑删除入口」。日志写入走 {@link OperationLogService} 与
 * {@code AuthService.writeLoginLog}，是两条完全独立的路径 —— 这样即使日志查询
 * 出问题也不会影响业务写入。
 * <p>
 * <b>导出为什么单独写一条路径：</b>日志可能上百万行，先查全量再交给 EasyExcel
 * 会把堆打满。这里按 2000 行分页流式写，内存占用与总行数无关（同 ExportService 的做法）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LogService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final int EXPORT_BATCH = 2000;
    private static final int EXPORT_MAX = 100_000;
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final SysLoginLogMapper loginLogMapper;
    private final SysOperationLogMapper operationLogMapper;
    private final OperationLogService operationLogService;

    // =========================================================
    // 登录日志
    // =========================================================

    public PageResult<LoginLogVO> pageLoginLogs(LogQuery q) {
        LambdaQueryWrapper<SysLoginLog> w = Wrappers.<SysLoginLog>lambdaQuery();
        if (StringUtils.hasText(q.getKeyword())) {
            String k = q.getKeyword().trim();
            w.and(x -> x.like(SysLoginLog::getUsername, k).or().like(SysLoginLog::getIp, k));
        }
        if (q.getResult() != null) {
            w.eq(SysLoginLog::getResult, q.getResult());
        }
        LocalDateTime start = parseTime(q.getStartTime(), "起始时间");
        LocalDateTime end = parseTime(q.getEndTime(), "截止时间");
        if (start != null) {
            w.ge(SysLoginLog::getCreateTime, start);
        }
        if (end != null) {
            w.le(SysLoginLog::getCreateTime, end);
        }
        w.orderByDesc(SysLoginLog::getId);

        Page<SysLoginLog> p = loginLogMapper.selectPage(new Page<>(normPage(q.getPage()), normSize(q.getSize())), w);
        List<LoginLogVO> list = new ArrayList<>(p.getRecords().size());
        for (SysLoginLog e : p.getRecords()) {
            LoginLogVO vo = new LoginLogVO();
            BeanUtils.copyProperties(e, vo);
            list.add(vo);
        }
        return PageResult.of(list, p.getTotal(), p.getCurrent(), p.getSize());
    }

    // =========================================================
    // 操作日志
    // =========================================================

    public PageResult<OperationLogVO> pageOperationLogs(LogQuery q) {
        LambdaQueryWrapper<SysOperationLog> w = Wrappers.<SysOperationLog>lambdaQuery();
        if (StringUtils.hasText(q.getKeyword())) {
            String k = q.getKeyword().trim();
            w.and(x -> x.like(SysOperationLog::getUsername, k)
                    .or().like(SysOperationLog::getDetail, k)
                    .or().like(SysOperationLog::getAction, k));
        }
        if (q.getResult() != null) {
            w.eq(SysOperationLog::getResult, q.getResult());
        }
        if (StringUtils.hasText(q.getModule())) {
            w.eq(SysOperationLog::getModule, q.getModule().trim());
        }
        if (StringUtils.hasText(q.getAction())) {
            w.eq(SysOperationLog::getAction, q.getAction().trim());
        }
        LocalDateTime start = parseTime(q.getStartTime(), "起始时间");
        LocalDateTime end = parseTime(q.getEndTime(), "截止时间");
        if (start != null) {
            w.ge(SysOperationLog::getCreateTime, start);
        }
        if (end != null) {
            w.le(SysOperationLog::getCreateTime, end);
        }
        w.orderByDesc(SysOperationLog::getId);

        Page<SysOperationLog> p = operationLogMapper.selectPage(
                new Page<>(normPage(q.getPage()), normSize(q.getSize())), w);
        List<OperationLogVO> list = new ArrayList<>(p.getRecords().size());
        for (SysOperationLog e : p.getRecords()) {
            OperationLogVO vo = new OperationLogVO();
            BeanUtils.copyProperties(e, vo);
            list.add(vo);
        }
        return PageResult.of(list, p.getTotal(), p.getCurrent(), p.getSize());
    }

    /**
     * 操作日志里出现过的模块名，供前端筛选下拉。
     * <p>
     * 用 {@code SELECT DISTINCT module} 而不是写死常量：模块名散落在各 Service 的
     * {@code operationLogService.write(...)} 调用里，写死一份清单必然会漏。
     */
    public List<String> distinctModules() {
        List<String> list = new ArrayList<>();
        for (SysOperationLog e : operationLogMapper.selectList(
                Wrappers.<SysOperationLog>lambdaQuery()
                        .select(SysOperationLog::getModule)
                        .groupBy(SysOperationLog::getModule)
                        .orderByAsc(SysOperationLog::getModule))) {
            if (e.getModule() != null) {
                list.add(e.getModule());
            }
        }
        return list;
    }

    // =========================================================
    // 导出
    // =========================================================

    public void exportLoginLogs(LogQuery q, HttpServletResponse response, HttpServletRequest request)
            throws IOException {
        prepareResponse(response, "登录日志");
        List<List<String>> head = List.of(
                List.of("编号"), List.of("账号"), List.of("用户ID"), List.of("结果"),
                List.of("原因"), List.of("IP"), List.of("浏览器"), List.of("时间"));

        try (ExcelWriter writer = EasyExcel.write(response.getOutputStream()).build()) {
            WriteSheet sheet = EasyExcel.writerSheet(0, "登录日志").head(head).build();
            long page = 1;
            long written = 0;
            while (written < EXPORT_MAX) {
                LogQuery batch = copyQuery(q);
                batch.setPage((int) page);
                batch.setSize(EXPORT_BATCH);
                List<LoginLogVO> rows = pageLoginLogs(batch).getRecords();
                if (rows.isEmpty()) {
                    break;
                }
                List<List<Object>> data = new ArrayList<>(rows.size());
                for (LoginLogVO r : rows) {
                    data.add(List.of(
                            nz(r.getId()), nz(r.getUsername()), nz(r.getUserId()),
                            r.getResult() != null && r.getResult() == 1 ? "成功" : "失败",
                            nz(r.getReason()), nz(r.getIp()), nz(r.getUserAgent()), nz(r.getCreateTime())));
                }
                writer.write(data, sheet);
                written += rows.size();
                if (rows.size() < EXPORT_BATCH) {
                    break;
                }
                page++;
            }
            if (written == 0) {
                // 与导出模块同样的坑：没有数据时 EasyExcel 不建 Sheet，文件会是个空壳
                writer.write(List.of(List.of("暂无日志", "", "", "", "", "", "", "")), sheet);
            }
            log.info("导出登录日志 {} 行", written);
            operationLogService.write("日志管理", "导出登录日志", "sys_login_log", null,
                    "导出登录日志，共 " + written + " 行", request);
        }
    }

    public void exportOperationLogs(LogQuery q, HttpServletResponse response, HttpServletRequest request)
            throws IOException {
        prepareResponse(response, "操作日志");
        List<List<String>> head = List.of(
                List.of("编号"), List.of("账号"), List.of("用户ID"), List.of("模块"), List.of("动作"),
                List.of("目标类型"), List.of("目标ID"), List.of("详情"), List.of("结果"),
                List.of("IP"), List.of("时间"));

        try (ExcelWriter writer = EasyExcel.write(response.getOutputStream()).build()) {
            WriteSheet sheet = EasyExcel.writerSheet(0, "操作日志").head(head).build();
            long page = 1;
            long written = 0;
            while (written < EXPORT_MAX) {
                LogQuery batch = copyQuery(q);
                batch.setPage((int) page);
                batch.setSize(EXPORT_BATCH);
                List<OperationLogVO> rows = pageOperationLogs(batch).getRecords();
                if (rows.isEmpty()) {
                    break;
                }
                List<List<Object>> data = new ArrayList<>(rows.size());
                for (OperationLogVO r : rows) {
                    data.add(List.of(
                            nz(r.getId()), nz(r.getUsername()), nz(r.getUserId()), nz(r.getModule()),
                            nz(r.getAction()), nz(r.getTargetType()), nz(r.getTargetId()),
                            nz(r.getDetail()), r.getResult() != null && r.getResult() == 1 ? "成功" : "失败",
                            nz(r.getIp()), nz(r.getCreateTime())));
                }
                writer.write(data, sheet);
                written += rows.size();
                if (rows.size() < EXPORT_BATCH) {
                    break;
                }
                page++;
            }
            if (written == 0) {
                writer.write(List.of(List.of("暂无日志", "", "", "", "", "", "", "", "", "", "")), sheet);
            }
            log.info("导出操作日志 {} 行", written);
            operationLogService.write("日志管理", "导出操作日志", "sys_operation_log", null,
                    "导出操作日志，共 " + written + " 行", request);
        }
    }

    // =========================================================
    // 私有
    // =========================================================

    private LogQuery copyQuery(LogQuery src) {
        LogQuery q = new LogQuery();
        q.setKeyword(src.getKeyword());
        q.setResult(src.getResult());
        q.setModule(src.getModule());
        q.setAction(src.getAction());
        q.setStartTime(src.getStartTime());
        q.setEndTime(src.getEndTime());
        return q;
    }

    private LocalDateTime parseTime(String text, String field) {
        if (!StringUtils.hasText(text)) {
            return null;
        }
        try {
            return LocalDateTime.parse(text.trim(), FMT);
        } catch (DateTimeParseException e) {
            throw BizException.badRequest(field + "格式应为 yyyy-MM-dd HH:mm:ss");
        }
    }

    private void prepareResponse(HttpServletResponse response, String name) {
        response.reset();
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setCharacterEncoding(null);
        String filename = name + "_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss")) + ".xlsx";
        String encoded = URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20");
        response.setHeader("Content-Disposition", "attachment; filename=\"logs.xlsx\"; filename*=UTF-8''" + encoded);
        response.setHeader("Access-Control-Expose-Headers", "Content-Disposition");
    }

    private Object nz(Object v) {
        return v == null ? "" : v;
    }

    private int normPage(Integer page) {
        return page == null || page < 1 ? 1 : page;
    }

    private int normSize(Integer size) {
        if (size == null || size < 1) {
            return 20;
        }
        return Math.min(size, MAX_PAGE_SIZE);
    }
}
