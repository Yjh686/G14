package com.g14.g14.login.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.g14.g14.login.entity.ClassInfo;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 班级视图对象（权限演示用）。
 */
@Data
public class ClassVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;
    private String name;
    private String inviteCode;
    private Long teacherId;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;

    public static ClassVO from(ClassInfo clazz) {
        ClassVO vo = new ClassVO();
        vo.id = clazz.getId();
        vo.name = clazz.getName();
        vo.inviteCode = clazz.getInviteCode();
        vo.teacherId = clazz.getTeacherId();
        vo.createdAt = clazz.getCreatedAt();
        return vo;
    }
}
