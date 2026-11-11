package com.yjh.backend1.module.template.dto;

import lombok.Data;

/**
 * 模板中心 / 我的模板 的查询条件。
 */
@Data
public class TemplateQuery {

    /** 关键词，匹配模板名称 */
    private String keyword;
    /** 分类 */
    private String category;
    /** 状态：仅 scope=mine 或审核视角有效；模板中心忽略该字段 */
    private String status;
    /**
     * 视角：
     * <ul>
     *   <li>{@code browse}（默认）模板中心 —— 只有 APPROVED</li>
     *   <li>{@code mine} 我共享的 —— 全部状态</li>
     *   <li>{@code audit} 待审核列表 —— 需要 template:audit</li>
     * </ul>
     */
    private String scope;
    /** 排序：rating（评分优先，默认）/ latest（最新） / used（最多使用） */
    private String order;
    private Integer page;
    private Integer size;
}
