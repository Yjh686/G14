package com.yjh.backend1.common;

import lombok.Data;

import java.io.Serializable;

/**
 * 统一响应体：{@code {code, msg, data, timestamp}}。
 *
 * @param <T> 业务数据类型
 */
@Data
public class Result<T> implements Serializable {

    private int code;
    private String msg;
    private T data;
    private long timestamp;

    private Result(int code, String msg, T data) {
        this.code = code;
        this.msg = msg;
        this.data = data;
        this.timestamp = System.currentTimeMillis();
    }

    public static <T> Result<T> ok(T data) {
        return new Result<>(ResultCode.SUCCESS.getCode(), ResultCode.SUCCESS.getMsg(), data);
    }

    public static <T> Result<T> ok(T data, String msg) {
        return new Result<>(ResultCode.SUCCESS.getCode(), msg, data);
    }

    public static Result<Void> ok() {
        return new Result<>(ResultCode.SUCCESS.getCode(), ResultCode.SUCCESS.getMsg(), null);
    }

    public static Result<Void> okMsg(String msg) {
        return new Result<>(ResultCode.SUCCESS.getCode(), msg, null);
    }

    public static <T> Result<T> fail(ResultCode rc) {
        return new Result<>(rc.getCode(), rc.getMsg(), null);
    }

    public static <T> Result<T> fail(ResultCode rc, String msg) {
        return new Result<>(rc.getCode(), msg, null);
    }

    public static <T> Result<T> fail(int code, String msg) {
        return new Result<>(code, msg, null);
    }

    public boolean isSuccess() {
        return this.code == ResultCode.SUCCESS.getCode();
    }
}
