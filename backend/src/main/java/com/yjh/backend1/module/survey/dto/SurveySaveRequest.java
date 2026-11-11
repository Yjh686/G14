package com.yjh.backend1.module.survey.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 新建 / 修改问卷的元信息入参。
 * <p>
 * 新建（POST）与修改（PUT）共用同一个对象：
 * 新建时校验题目无关的元信息即可；修改时若问卷已发布，
 * 服务层只允许改标题/简介/截止时间（见 R-SUR-2 发布后锁定结构）。
 */
@Data
public class SurveySaveRequest {

    @NotBlank(message = "问卷标题不能为空")
    @Size(max = 120, message = "标题不能超过 120 字")
    private String title;

    @Size(max = 1000, message = "简介不能超过 1000 字")
    private String description;

    /** 分类：TEACHING / ACTIVITY / SATISFACTION / OTHER，null 视为 OTHER */
    private String category;

    /** 是否匿名：1=匿名 0=实名，null 视为 0 */
    private Integer anonymous;

    /** 是否限填一次：1=是 0=否，null 视为 1 */
    private Integer limitOnce;

    /** 开始时间，null=立即开始。格式 yyyy-MM-dd HH:mm:ss */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime startTime;

    /** 截止时间，null=长期有效 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime endTime;
}
