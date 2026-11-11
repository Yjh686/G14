package com.yjh.backend1.module.survey.enums;

import com.yjh.backend1.common.BizException;

/**
 * 题型。
 * <p>
 * 只做五种，「矩阵题/排序题/文件上传」等按需求层结论不进本期 P0。
 */
public enum QuestionType {

    /** 单选：必须有 >=2 个选项 */
    SINGLE(true),
    /** 多选：必须有 >=2 个选项 */
    MULTIPLE(true),
    /** 单行文本 */
    TEXT(false),
    /** 多行文本：可设最大字数 */
    TEXTAREA(false),
    /** 评分（1~5 星，可选上限） */
    RATING(false);

    /** 是否依赖选项 */
    private final boolean needOptions;

    QuestionType(boolean needOptions) {
        this.needOptions = needOptions;
    }

    public boolean isNeedOptions() {
        return needOptions;
    }

    public boolean isText() {
        return this == TEXT || this == TEXTAREA;
    }

    public static QuestionType of(String code) {
        if (code == null) {
            return null;
        }
        for (QuestionType t : values()) {
            if (t.name().equalsIgnoreCase(code)) {
                return t;
            }
        }
        return null;
    }

    /** 严格解析，非法值抛 400 */
    public static QuestionType parse(String code) {
        QuestionType t = of(code);
        if (t == null) {
            throw BizException.badRequest("不支持的题型：" + code);
        }
        return t;
    }
}
