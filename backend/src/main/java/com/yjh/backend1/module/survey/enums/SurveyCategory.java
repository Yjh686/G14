package com.yjh.backend1.module.survey.enums;

/**
 * 问卷分类。用于「我的问卷」筛选与问卷大厅归类。
 */
public enum SurveyCategory {

    /** 教学反馈 */
    TEACHING("教学反馈"),
    /** 活动报名 */
    ACTIVITY("活动报名"),
    /** 满意度调查 */
    SATISFACTION("满意度"),
    /** 其他 */
    OTHER("其他");

    private final String label;

    SurveyCategory(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    public static SurveyCategory of(String code) {
        if (code == null) {
            return OTHER;
        }
        for (SurveyCategory c : values()) {
            if (c.name().equalsIgnoreCase(code)) {
                return c;
            }
        }
        return OTHER;
    }

    /** 校验用：非法值返回 null，由调用方决定是否报错 */
    public static SurveyCategory parse(String code) {
        if (code == null) {
            return null;
        }
        for (SurveyCategory c : values()) {
            if (c.name().equalsIgnoreCase(code)) {
                return c;
            }
        }
        return null;
    }
}
