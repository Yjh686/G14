package com.g14.g14.login.entity;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 问卷实体，对应表 questionnaire。
 */
@Data
public class Questionnaire implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;
    private Long classId;
    private String title;
    /** 创建老师 sys_user.id */
    private Long creatorId;
    /** 1=已发布（学生可见） 0=草稿（学生不可见） */
    private Integer status;
    private LocalDateTime createdAt;

    public boolean published() {
        return status != null && status == 1;
    }
}
