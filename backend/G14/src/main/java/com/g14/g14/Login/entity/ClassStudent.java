package com.g14.g14.login.entity;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 班级成员，对应表 class_student。
 */
@Data
public class ClassStudent implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;
    private Long classId;
    private Long studentId;
    private LocalDateTime createdAt;
}
