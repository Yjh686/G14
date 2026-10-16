package com.g14.g14.login.dto;

import lombok.Data;

/**
 * 用户分页查询条件。
 *
 * <p>前端 {@code adminListUsers()} 传的是 {@code pageNum}/{@code pageSize}，
 * 每页上限 100（超出被截断，并在响应中回显生效值）。
 */
@Data
public class UserQuery {

    /** 账号或姓名模糊匹配 */
    private String keyword;

    /** 角色过滤：STUDENT / TEACHER / ADMIN，空表示全部 */
    private String role;

    /** 状态过滤：1 / 0，null 表示全部 */
    private Integer status;

    private Integer pageNum = 1;

    private Integer pageSize = 10;

    /** 每页上限，防止一次拉全表 */
    public static final int MAX_PAGE_SIZE = 100;

    public int getEffectivePageNum() {
        return pageNum == null || pageNum < 1 ? 1 : pageNum;
    }

    /** 生效的分页大小（1 ~ 100），响应中回显的也是该值 */
    public int getEffectivePageSize() {
        if (pageSize == null || pageSize < 1) {
            return 10;
        }
        return Math.min(pageSize, MAX_PAGE_SIZE);
    }

    public int getOffset() {
        return (getEffectivePageNum() - 1) * getEffectivePageSize();
    }
}
