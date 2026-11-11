package com.yjh.backend1.module.log.dto;

import lombok.Data;

/**
 * 日志查询条件。登录日志与操作日志共用，各字段与对应接口的语义见 {@code LogController}。
 * <p>
 * 时间范围用字符串而不是 {@code LocalDate}：前端 {@code el-date-picker} 传的是
 * {@code yyyy-MM-dd HH:mm:ss}，直接用字符串在这里做 {@code <=} 比较也符合字典序，
 * 少一层解析出错的机会。非法格式由 Service 转成 400，而不是抛 500。
 */
@Data
public class LogQuery {

    /** 账号/姓名模糊搜索（登录日志匹配 username，操作日志匹配 username 或 detail） */
    private String keyword;
    /** 结果：1=成功 0=失败 */
    private Integer result;
    /** 操作日志专用：模块，如「问卷管理」 */
    private String module;
    /** 操作日志专用：动作，如「发布问卷」 */
    private String action;
    /** 起始时间 yyyy-MM-dd HH:mm:ss（含） */
    private String startTime;
    /** 截止时间 yyyy-MM-dd HH:mm:ss（含） */
    private String endTime;

    private Integer page = 1;
    private Integer size = 20;
}
