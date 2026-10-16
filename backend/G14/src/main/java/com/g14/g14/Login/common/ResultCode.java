package com.g14.g14.login.common;

/**
 * 统一业务状态码。
 *
 * <p>编码规则（与前端 <code>src/api/request.js</code> 的拦截器逐条对齐）：
 * <pre>
 *   HTTP 400 ← 400xx   参数 / 密码校验类
 *   HTTP 401 ← 401xx   认证类（未登录、令牌无效、凭据错误）
 *   HTTP 403 ← 403xx   授权类（越权、禁用、强制改密、锁定）
 *   HTTP 404 ← 404xx   资源不存在
 *   HTTP 409 ← 409xx   唯一性冲突
 *   HTTP 500 ← 500xx   服务端异常
 * </pre>
 * 即「HTTP 状态码 = 业务码的高位归类」，前端既能按 status 粗分流，也能按 code 精细提示。
 */
public enum ResultCode {

    /* ---------- 成功 ---------- */
    SUCCESS(200, 200, "成功"),

    /* ---------- 400 参数 / 密码 ---------- */
    PARAM_ERROR(40000, 400, "参数错误"),
    PASSWORD_INVALID(40001, 400, "密码不符合安全要求"),
    OLD_PASSWORD_WRONG(40002, 400, "原密码错误"),
    PASSWORD_SAME_AS_OLD(40003, 400, "新密码不能与原密码相同"),

    /* ---------- 401 认证 ---------- */
    UNAUTHORIZED(40100, 401, "未登录或登录已过期"),
    TOKEN_MISSING(40101, 401, "未登录或缺少令牌"),
    TOKEN_INVALID(40102, 401, "令牌无效"),
    TOKEN_REVOKED(40103, 401, "令牌已失效，请重新登录"),
    BAD_CREDENTIALS(40104, 401, "账号或密码错误"),

    /* ---------- 403 授权 ---------- */
    FORBIDDEN(40300, 403, "无权限访问"),
    ACCOUNT_DISABLED(40301, 403, "账号已被禁用"),
    MUST_CHANGE_PASSWORD(40302, 403, "请先修改初始密码"),
    ACCOUNT_LOCKED(40303, 403, "账号已锁定，请稍后重试"),
    CANNOT_OPERATE_SELF(40304, 403, "不能对当前登录账号执行该操作"),
    CANNOT_OPERATE_ADMIN(40305, 403, "不能对管理员账号执行该操作"),

    /* ---------- 404 资源 ---------- */
    NOT_FOUND(40400, 404, "资源不存在"),
    USER_NOT_FOUND(40401, 404, "用户不存在"),
    /** 班级不存在与无权访问共用同一码与同一文案，防止攻击者通过响应差异枚举班级 ID */
    CLASS_NOT_FOUND(40402, 404, "班级不存在或无权访问"),
    /** 问卷不存在、未发布、或不属于当前用户可见范围，同样统一响应 */
    QUESTIONNAIRE_NOT_FOUND(40403, 404, "问卷不存在或不可见"),

    /* ---------- 409 冲突 ---------- */
    ACCOUNT_EXISTS(40901, 409, "账号已存在"),
    PHONE_EXISTS(40902, 409, "手机号已被占用"),

    /* ---------- 500 ---------- */
    SERVER_ERROR(50000, 500, "服务异常");

    private final int code;
    private final int httpStatus;
    private final String message;

    ResultCode(int code, int httpStatus, String message) {
        this.code = code;
        this.httpStatus = httpStatus;
        this.message = message;
    }

    public int getCode() {
        return code;
    }

    public int getHttpStatus() {
        return httpStatus;
    }

    public String getMessage() {
        return message;
    }
}
