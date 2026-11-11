package com.yjh.backend1.common;

/**
 * 业务异常。由 {@link GlobalExceptionHandler} 统一转换为响应体。
 * <p>
 * 用法示例：
 * <pre>
 *   throw BizException.notFound("用户不存在");
 *   throw BizException.forbidden("无权访问该资源");
 * </pre>
 */
public class BizException extends RuntimeException {

    private final int code;

    public BizException(int code, String msg) {
        super(msg);
        this.code = code;
    }

    public BizException(ResultCode rc, String msg) {
        super(msg);
        this.code = rc.getCode();
    }

    public static BizException badRequest(String msg) {
        return new BizException(ResultCode.BAD_REQUEST, msg);
    }

    public static BizException unauthorized(String msg) {
        return new BizException(ResultCode.UNAUTHORIZED, msg);
    }

    public static BizException forbidden(String msg) {
        return new BizException(ResultCode.FORBIDDEN, msg);
    }

    public static BizException notFound(String msg) {
        return new BizException(ResultCode.NOT_FOUND, msg);
    }

    public static BizException conflict(String msg) {
        return new BizException(ResultCode.CONFLICT, msg);
    }

    public int getCode() {
        return code;
    }
}
