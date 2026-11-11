package com.yjh.backend1.module.template.enums;

import com.yjh.backend1.common.BizException;

/**
 * 模板审核状态。
 * <p>
 * 刻意没有 {@code DELETED}：删除由 {@code deleted=1} 表达。
 * 状态里再放一个 DELETED 会出现「status=DELETED 但 deleted=0」这种自相矛盾的数据，
 * 之后每个查询都得同时判断两个字段，漏一个就是把已删模板又露出来。
 */
public enum TemplateStatus {

    /** 待审核：共享者刚提交，只有共享者和审核员能看到 */
    PENDING("待审核"),
    /** 已通过：模板中心可见、可被使用与评分 */
    APPROVED("已通过"),
    /** 已驳回：附驳回原因，共享者可改后重新提交 */
    REJECTED("已驳回"),
    /** 已下架：共享者主动下线，不再出现在模板中心 */
    OFFLINE("已下架");

    private final String label;

    TemplateStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    public static TemplateStatus of(String code) {
        if (code == null) {
            return null;
        }
        for (TemplateStatus s : values()) {
            if (s.name().equalsIgnoreCase(code)) {
                return s;
            }
        }
        return null;
    }

    /** 严格解析，非法值抛 400 —— 让调用方传错状态时立刻发现，而不是被当成「全部」 */
    public static TemplateStatus parse(String code) {
        TemplateStatus s = of(code);
        if (s == null) {
            throw BizException.badRequest("未知的模板状态：" + code);
        }
        return s;
    }

    /** 是否会在模板中心对外可见 */
    public boolean browsable() {
        return this == APPROVED;
    }
}
