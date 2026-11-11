package com.yjh.backend1.module.answer.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 答卷头，对应表 {@code answer_sheet}。
 * <p>
 * 刻意不继承 {@code BaseEntity}：这张表没有 create_by/update_by。
 * 填写人记在 {@code userId}，而「谁提交的」恰好是匿名问卷必须隐藏的信息，
 * 若复用 BaseEntity 会让审计字段与匿名约束在语义上打架。
 * <p>
 * {@code userId} 存在的唯一目的是：①限填一次；②「我的填写记录」。
 * 任何面向发布者的接口（答卷列表 / 详情 / 导出）都必须用 DTO 把它裁掉。
 */
@Data
@TableName("answer_sheet")
public class AnswerSheet implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long surveyId;

    private Long userId;

    /**
     * 提交时对 {@code survey.limit_once} 的快照。
     * <p>
     * 不能查询时实时读取 survey 的当前值：问卷发布后限填设置即锁定，
     * 但历史答卷仍应按提交当时的规则解释，快照是唯一可靠的依据。
     */
    private Integer limitOnce;

    /** 是否有效：1=有效（计入统计）0=已标记无效 */
    private Integer isValid;

    private String invalidReason;

    @TableField(value = "create_time", fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(value = "update_time", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableLogic
    @TableField("deleted")
    private Integer deleted;

    public boolean valid() {
        return Integer.valueOf(1).equals(isValid);
    }
}
