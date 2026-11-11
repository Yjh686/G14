package com.yjh.backend1.module.template.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 模板评分，对应表 {@code template_rating}。
 * <p>
 * 不继承 {@link com.yjh.backend1.common.BaseEntity}：这张表没有 deleted 列。
 * 「一人一模板一票」由表上的 {@code uk_template_user} 唯一索引保证，
 * 改票走 update 同一行，所以留不下需要追溯的删除历史。
 */
@Data
@TableName("template_rating")
public class TemplateRating implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long templateId;
    private Long userId;
    /** 1~5 星 */
    private Integer score;

    @TableField(value = "create_time", fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(value = "update_time", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
