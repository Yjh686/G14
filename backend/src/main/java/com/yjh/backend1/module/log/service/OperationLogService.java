package com.yjh.backend1.module.log.service;

import com.yjh.backend1.common.RequestUtil;
import com.yjh.backend1.module.log.entity.SysOperationLog;
import com.yjh.backend1.module.log.mapper.SysOperationLogMapper;
import com.yjh.backend1.security.LoginUser;
import com.yjh.backend1.security.UserContext;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 操作日志写入。
 * <p>
 * 刻意做成「失败不影响主流程」：日志写不进去，业务该成功还是成功。
 * 反过来，如果日志失败就回滚业务，会把审计能力变成单点故障。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OperationLogService {

    private final SysOperationLogMapper mapper;

    public void write(String module, String action, String targetType, Long targetId, String detail, HttpServletRequest req) {
        write(module, action, targetType, targetId, detail, true, req);
    }

    public void write(String module, String action, String targetType, Long targetId,
                      String detail, boolean success, HttpServletRequest req) {
        try {
            LoginUser u = UserContext.get();
            SysOperationLog l = new SysOperationLog();
            if (u != null) {
                l.setUserId(u.getUserId());
                l.setUsername(u.getUsername());
            }
            l.setModule(module);
            l.setAction(action);
            l.setTargetType(targetType);
            l.setTargetId(targetId);
            l.setDetail(detail != null && detail.length() > 500 ? detail.substring(0, 500) : detail);
            l.setResult(success ? 1 : 0);
            l.setIp(RequestUtil.clientIp(req));
            l.setCreateTime(LocalDateTime.now());
            mapper.insert(l);
        } catch (Exception e) {
            log.warn("写操作日志失败 module={} action={}: {}", module, action, e.getMessage());
        }
    }
}
