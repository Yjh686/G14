package com.yjh.backend1.module.user.dto;

import lombok.Data;

/**
 * 用户查询条件。
 */
@Data
public class UserQuery {

    /** 账号/姓名模糊搜索 */
    private String keyword;
    /** 1=正常 0=禁用 */
    private Integer status;
    /** 0=待审核 1=已通过 2=已驳回 */
    private Integer auditStatus;
    /** ROLE_ADMIN / ROLE_TEACHER / ROLE_STUDENT */
    private String roleCode;

    private Integer page = 1;
    private Integer size = 20;
}
