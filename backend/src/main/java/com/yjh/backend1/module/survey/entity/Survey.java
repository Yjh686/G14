package com.yjh.backend1.module.survey.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.yjh.backend1.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 问卷实体，对应表 {@code survey}。
 * <p>
 * 状态相关字段全部用字符串存枚举名（{@code DRAFT}/{@code PUBLISHED}/...），
 * 不用数字码值：数据库里直接看得懂，排查问题时不用查对照表。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("survey")
public class Survey extends BaseEntity {

    /** 标题 */
    private String title;
    /** 简介 / 填写说明 */
    private String description;
    /** 分类：TEACHING / ACTIVITY / SATISFACTION / OTHER */
    private String category;
    /** 是否匿名：1=匿名 0=实名 */
    private Integer anonymous;
    /** 状态：DRAFT / PUBLISHED / PAUSED / CLOSED */
    private String status;
    /** 分享码，发布时生成；草稿为 NULL */
    private String shareCode;
    /** 开始时间，NULL=立即开始 */
    private LocalDateTime startTime;
    /** 截止时间，NULL=长期有效 */
    private LocalDateTime endTime;
    /** 是否限填一次：1=是 0=否 */
    private Integer limitOnce;
    /** 题目数量（冗余） */
    private Integer questionCount;
    /** 已回收答卷数（冗余） */
    private Integer answerCount;
    /** 首次发布时间 */
    private LocalDateTime publishedTime;
    /** 人为结束时间 */
    private LocalDateTime closedTime;

    public boolean anonymousEnabled() {
        return Integer.valueOf(1).equals(anonymous);
    }

    public boolean limitOnceEnabled() {
        return Integer.valueOf(1).equals(limitOnce);
    }

    /** 是否在有效期内（不判断状态，状态由调用方判断） */
    public boolean inTimeWindow(LocalDateTime now) {
        if (startTime != null && now.isBefore(startTime)) {
            return false;
        }
        return endTime == null || !now.isAfter(endTime);
    }
}
