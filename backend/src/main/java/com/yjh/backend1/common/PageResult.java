package com.yjh.backend1.common;

import com.baomidou.mybatisplus.core.metadata.IPage;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 统一分页响应体。
 */
@Data
public class PageResult<T> implements Serializable {

    /** 当前页数据 */
    private List<T> records;
    /** 总记录数 */
    private long total;
    /** 当前页码（从 1 开始） */
    private long page;
    /** 每页条数 */
    private long size;
    /** 总页数 */
    private long pages;

    public static <T> PageResult<T> of(IPage<T> p) {
        PageResult<T> r = new PageResult<>();
        r.setRecords(p.getRecords());
        r.setTotal(p.getTotal());
        r.setPage(p.getCurrent());
        r.setSize(p.getSize());
        r.setPages(p.getPages());
        return r;
    }

    public static <T> PageResult<T> of(List<T> records, long total, long page, long size) {
        PageResult<T> r = new PageResult<>();
        r.setRecords(records);
        r.setTotal(total);
        r.setPage(page);
        r.setSize(size);
        r.setPages(size <= 0 ? 0 : (total + size - 1) / size);
        return r;
    }

    public static <T> PageResult<T> empty(long page, long size) {
        return of(List.of(), 0, page, size);
    }
}
