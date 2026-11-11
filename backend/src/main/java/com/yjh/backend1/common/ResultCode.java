package com.yjh.backend1.common;

import lombok.Getter;

/**
 * 统一响应码。
 * <p>
 * 约定（全项目唯一标准）：业务响应的 {@code code} 与 HTTP 状态码保持一致，
 * 便于前端 axios 拦截器统一处理。
 * <ul>
 *   <li>200 成功</li>
 *   <li>400 参数错误</li>
 *   <li>401 未登录 / 登录失效</li>
 *   <li>403 无权限 / 越权访问</li>
 *   <li>404 资源不存在</li>
 *   <li>409 业务规则不满足（如问卷已结束、账号已锁定）</li>
 *   <li>500 服务器内部错误</li>
 * </ul>
 */
@Getter
public enum ResultCode {

    SUCCESS(200, "操作成功"),
    BAD_REQUEST(400, "请求参数错误"),
    UNAUTHORIZED(401, "未登录或登录已过期"),
    FORBIDDEN(403, "无权限执行该操作"),
    NOT_FOUND(404, "资源不存在"),
    CONFLICT(409, "业务规则不满足"),
    INTERNAL_ERROR(500, "服务器内部错误");

    private final int code;
    private final String msg;

    ResultCode(int code, String msg) {
        this.code = code;
        this.msg = msg;
    }
}
