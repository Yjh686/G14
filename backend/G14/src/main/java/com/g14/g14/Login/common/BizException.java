package com.g14.g14.login.common;

/**
 * 业务异常。由 Service / 拦截器抛出，统一交给 {@link GlobalExceptionHandler} 转换为 HTTP 响应。
 */
public class BizException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final ResultCode resultCode;

    public BizException(ResultCode resultCode) {
        super(resultCode.getMessage());
        this.resultCode = resultCode;
    }

    public BizException(ResultCode resultCode, String message) {
        super(message);
        this.resultCode = resultCode;
    }

    public ResultCode getResultCode() {
        return resultCode;
    }
}
