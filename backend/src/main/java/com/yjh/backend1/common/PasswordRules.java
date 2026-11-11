package com.yjh.backend1.common;

/**
 * 密码策略规则。
 * <p>
 * 从「系统配置」读取（FR-CFG-02），因此同一个校验方法要能接受不同规则：
 * 管理员把最小长度从 8 改到 10 之后，老密码仍然能登录（登录只用 BCrypt 比对，
 * 从不重跑策略），只有「设置新密码」时才会按新规则卡。
 *
 * @param minLength      最小长度
 * @param maxLength      最大长度
 * @param requireLetter  必须包含字母
 * @param requireDigit   必须包含数字
 * @param requireSpecial 必须包含特殊字符（非字母非数字非空白）
 */
public record PasswordRules(int minLength, int maxLength, boolean requireLetter,
                            boolean requireDigit, boolean requireSpecial) {

    /** 与建表脚本 05_schema_config.sql 的内置值一致，配置读不出来时用它兜底。 */
    public static final PasswordRules DEFAULT = new PasswordRules(8, 32, true, true, false);

    /** 生成给前端展示的规则文案，与后端校验逻辑保持同一份描述。 */
    public String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append(minLength).append("~").append(maxLength).append(" 位，不含空格");
        if (requireLetter || requireDigit || requireSpecial) {
            sb.append("，且必须包含");
            if (requireLetter) {
                sb.append("字母");
            }
            if (requireDigit) {
                sb.append(requireLetter ? "和数字" : "数字");
            }
            if (requireSpecial) {
                sb.append(requireLetter || requireDigit ? "和特殊字符" : "特殊字符");
            }
        }
        return sb.toString();
    }
}
