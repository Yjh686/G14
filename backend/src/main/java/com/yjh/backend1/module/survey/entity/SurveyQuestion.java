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
 * 题目实体，对应表 {@code survey_question}。
 * <p>
 * 刻意不继承 {@link com.yjh.backend1.common.BaseEntity}：子表不做逻辑删除
 * （草稿阶段的题目删掉就是删掉，没有需要追溯的历史），
 * 也没有 {@code deleted} 列。审计字段仍然保留。
 */
@Data
@TableName("survey_question")
public class SurveyQuestion implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long surveyId;
    /** 题型：SINGLE / MULTIPLE / TEXT / TEXTAREA / RATING */
    private String questionType;
    /** 题干 */
    private String title;
    /** 补充说明 */
    private String description;
    /** 是否必答：1=是 0=否 */
    private Integer isRequired;
    /** 文本题最大字数 */
    private Integer maxLength;
    /** 评分题最高分 */
    private Integer ratingMax;
    /** 排序号，从 1 递增 */
    private Integer sortNo;

    @TableField(value = "create_by", fill = FieldFill.INSERT)
    private Long createBy;

    @TableField(value = "create_time", fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(value = "update_by", fill = FieldFill.INSERT_UPDATE)
    private Long updateBy;

    @TableField(value = "update_time", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    public boolean required() {
        return Integer.valueOf(1).equals(isRequired);
    }
}
