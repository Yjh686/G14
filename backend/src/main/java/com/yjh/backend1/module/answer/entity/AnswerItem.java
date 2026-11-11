package com.yjh.backend1.module.answer.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 答案明细，对应表 {@code answer_item}：一行一题。
 * <p>
 * 三种题型共用一个表，靠三个列表达：
 * <ul>
 *   <li>单选 / 多选 → {@code optionValues}（选项 ID 数组；单选也存长度为 1 的数组，取值方式统一）</li>
 *   <li>文本 / 多行文本 → {@code textValue}</li>
 *   <li>评分 → {@code numberValue}</li>
 * </ul>
 * 不拆成三张表：统计时按题目聚合，拆表就得 union，收益为零。
 * <p>
 * {@code autoResultMap = true} + {@link JacksonTypeHandler} 是让
 * {@code option_values} 这个 JSON 列与 {@code List<Long>} 互转的必要条件，
 * 少任何一半（注解或 autoResultMap），查询回来都会是字符串或直接报类型错误。
 */
@Data
@TableName(value = "answer_item", autoResultMap = true)
public class AnswerItem implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long sheetId;

    /** 冗余的问卷 ID，统计时按问卷聚合可省一次 join */
    private Long surveyId;

    private Long questionId;

    /** 选项 ID 数组，JSON 列 */
    @TableField(value = "option_values", typeHandler = JacksonTypeHandler.class)
    private List<Long> optionValues;

    private String textValue;

    private Integer numberValue;

    @TableField(value = "create_time", fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
