package com.yjh.backend1.module.survey.enums;

/**
 * 问卷状态机。
 * <pre>
 *   DRAFT ──发布──> PUBLISHED ──暂停──> PAUSED ──恢复──> PUBLISHED
 *                       │                  │
 *                       └────结束──────────┴──> CLOSED（不可逆）
 * </pre>
 * 删除不在此枚举中表达，由 {@code survey.deleted=1} 承担。
 * 理由：一个对象的状态只能用一处权威表达，否则容易出现
 * {@code status=CLOSED} 但 {@code deleted=1} 的矛盾数据。
 */
public enum SurveyStatus {

    /** 草稿：可编辑题目，不可填写 */
    DRAFT,
    /** 已发布：题目结构锁定，可填写 */
    PUBLISHED,
    /** 已暂停：暂停回收，已填数据保留，不可新填 */
    PAUSED,
    /** 已结束：不可逆，不可填写，可查看答卷与统计 */
    CLOSED;

    public static SurveyStatus of(String code) {
        if (code == null) {
            return null;
        }
        for (SurveyStatus s : values()) {
            if (s.name().equalsIgnoreCase(code)) {
                return s;
            }
        }
        return null;
    }

    /** 是否可被填写 */
    public boolean fillable() {
        return this == PUBLISHED;
    }

    /** 是否允许修改题目结构（发布后锁定） */
    public boolean structureEditable() {
        return this == DRAFT;
    }
}
