package com.yjh.backend1.module.answer.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 填写草稿，对应表 {@code survey_draft}。
 * <p>
 * 整卷作答原样存 JSON：草稿不参与统计、不参与导出，唯一用途是还回前端。
 * 存 JSON 免去「增删改题目行」的同步成本，也天然支持「只答了一半」。
 * <p>
 * {@code answersJson} 用 {@code String} 而非结构化对象，是刻意的：
 * 保存时不做解析，意味着后端永远不会因为「草稿里有一道题被删了」而拒绝保存。
 * 问卷发布后题目已锁定，草稿只会在 DRAFT 之后产生，这个风险本就很小，
 * 但「保存草稿」是高频操作，能省一次解析就省一次。
 */
@Data
@TableName("survey_draft")
public class SurveyDraft implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long surveyId;

    private Long userId;

    /** 整卷作答 JSON 字符串 */
    private String answersJson;

    @TableField(value = "create_time", fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(value = "update_time", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
