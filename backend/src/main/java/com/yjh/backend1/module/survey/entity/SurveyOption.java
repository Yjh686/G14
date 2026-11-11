package com.yjh.backend1.module.survey.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 选项实体，对应表 {@code survey_question_option}。
 * <p>
 * {@code surveyId} 是冗余列：统计「某问卷各选项被选次数」时可按 survey_id
 * 直接聚合，省一次 join。写入时由服务层保证与题目的 survey_id 一致。
 */
@Data
@TableName("survey_question_option")
public class SurveyOption implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long questionId;
    /** 冗余的问卷 ID，便于按问卷聚合统计 */
    private Long surveyId;
    /** 选项文本 */
    private String content;
    /** 排序号，从 1 递增 */
    private Integer sortNo;

    @TableField(value = "create_time", fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
