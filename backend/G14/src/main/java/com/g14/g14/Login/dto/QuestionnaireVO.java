package com.g14.g14.login.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.g14.g14.login.entity.Questionnaire;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 问卷视图对象（权限演示用）。
 */
@Data
public class QuestionnaireVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;
    private Long classId;
    private String title;
    private Long creatorId;
    /** 1=已发布 0=草稿 */
    private Integer status;
    private Boolean published;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;

    public static QuestionnaireVO from(Questionnaire q) {
        QuestionnaireVO vo = new QuestionnaireVO();
        vo.id = q.getId();
        vo.classId = q.getClassId();
        vo.title = q.getTitle();
        vo.creatorId = q.getCreatorId();
        vo.status = q.getStatus();
        vo.published = q.published();
        vo.createdAt = q.getCreatedAt();
        return vo;
    }
}
