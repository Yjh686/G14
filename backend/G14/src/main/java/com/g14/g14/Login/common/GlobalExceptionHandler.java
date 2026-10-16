package com.g14.g14.login.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import javax.validation.ConstraintViolation;
import javax.validation.ConstraintViolationException;
import java.util.stream.Collectors;

/**
 * 全局异常处理器。
 *
 * <p>拦截器 {@code preHandle} 中抛出的异常同样会被本处理器捕获
 * （Spring 会用 HandlerExceptionResolver 处理），因此鉴权失败也能返回统一结构体。
 *
 * <p>关键约定：HTTP 状态码 = {@link ResultCode#getHttpStatus()}，
 * 与响应体中的业务码同源，前端可按 status 粗分流、按 code 精细提示。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 业务异常：按 ResultCode 约定的 HTTP 状态码返回。 */
    @ExceptionHandler(BizException.class)
    public ResponseEntity<Result<Void>> handleBiz(BizException e) {
        ResultCode rc = e.getResultCode();
        if (log.isDebugEnabled()) {
            log.debug("业务异常 code={} msg={}", rc.getCode(), e.getMessage());
        }
        return ResponseEntity.status(rc.getHttpStatus())
                .body(Result.fail(rc.getCode(), e.getMessage()));
    }

    /** @RequestBody + @Valid 校验失败 → 400/40000 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Result<Void>> handleValid(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return badRequest(msg);
    }

    /** 表单 / 查询参数绑定校验失败 → 400/40000 */
    @ExceptionHandler(BindException.class)
    public ResponseEntity<Result<Void>> handleBind(BindException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return badRequest(msg);
    }

    /** 方法参数上的 @NotBlank / @Pattern 等约束失败 → 400/40000 */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Result<Void>> handleConstraint(ConstraintViolationException e) {
        String msg = e.getConstraintViolations().stream()
                .map(ConstraintViolation::getMessage)
                .collect(Collectors.joining("; "));
        return badRequest(msg);
    }

    @ExceptionHandler({MissingServletRequestParameterException.class, HttpMessageNotReadableException.class})
    public ResponseEntity<Result<Void>> handleBadRequest(Exception e) {
        return badRequest("请求参数格式不正确");
    }

    /**
     * 请求方法不支持 → 统一转成 400/40000。
     * <p>前端契约测试刻意用 PUT 打 {@code /admin/users/{id}/password/reset}（正确方法是 POST），
     * 断言得到的必须是 400/40000 而不是裸 405，以保证前端只需处理一套错误码。
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Result<Void>> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        return badRequest("请求方法不支持: " + e.getMethod());
    }

    /** 兜底：不向客户端泄漏堆栈，仅记录服务端日志。 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleOther(Exception e) {
        log.error("未捕获异常", e);
        return ResponseEntity.status(ResultCode.SERVER_ERROR.getHttpStatus())
                .body(Result.fail(ResultCode.SERVER_ERROR));
    }

    private ResponseEntity<Result<Void>> badRequest(String message) {
        return ResponseEntity.status(ResultCode.PARAM_ERROR.getHttpStatus())
                .body(Result.fail(ResultCode.PARAM_ERROR, message));
    }
}
