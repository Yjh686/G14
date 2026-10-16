package com.g14.g14.login.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * 通用分页结果。
 *
 * <p>字段名由前端 {@code views/admin/UserManage.vue} 直接读取：
 * {@code list} / {@code total} / {@code pageNum} / {@code pageSize}。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PageResult<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    private long total;
    private int pageNum;
    private int pageSize;
    private List<T> list;
}
