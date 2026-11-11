package com.yjh.backend1.common;

/**
 * 密码策略校验。
 * <p>
 * 规则本身来自系统配置（FR-CFG-02），由 {@link PasswordRules} 承载；
 * 本类只负责「按给定规则判断一个明文密码合不合规」。
 * <p>
 * 保留一组不传 rules 的重载委托给 {@link PasswordRules#DEFAULT}，好让
 * 注册/改密/建号/导入这类调用点在拿到配置化规则之前仍能工作；
 * 需要按管理员配置校验时显式传 rules。
 */
public final class PasswordPolicy {

    public static final int MIN_LEN = PasswordRules.DEFAULT.minLength();
    public static final int MAX_LEN = PasswordRules.DEFAULT.maxLength();

    private PasswordPolicy() {
    }

    /**
     * 按默认规则校验。
     *
     * @return null 表示通过；否则返回给用户看的中文提示
     */
    public static String validate(String pwd) {
        return validate(pwd, PasswordRules.DEFAULT);
    }

    /**
     * 按指定规则校验。
     *
     * @return null 表示通过；否则返回给用户看的中文提示
     */
    public static String validate(String pwd, PasswordRules rules) {
        PasswordRules r = rules == null ? PasswordRules.DEFAULT : rules;
        if (pwd == null || pwd.isEmpty()) {
            return "密码不能为空";
        }
        if (pwd.length() < r.minLength() || pwd.length() > r.maxLength()) {
            return "密码长度需为 " + r.minLength() + "~" + r.maxLength() + " 位";
        }
        if (pwd.chars().anyMatch(Character::isWhitespace)) {
            return "密码不能包含空格";
        }
        boolean hasLetter = pwd.chars().anyMatch(Character::isLetter);
        boolean hasDigit = pwd.chars().anyMatch(Character::isDigit);
        boolean hasSpecial = pwd.chars().anyMatch(c -> !Character.isLetterOrDigit(c));
        if (r.requireLetter() && !hasLetter) {
            return r.requireDigit() ? "密码必须同时包含字母和数字" : "密码必须包含字母";
        }
        if (r.requireDigit() && !hasDigit) {
            return r.requireLetter() ? "密码必须同时包含字母和数字" : "密码必须包含数字";
        }
        if (r.requireSpecial() && !hasSpecial) {
            return "密码必须包含特殊字符（如 !@#$%）";
        }
        return null;
    }

    public static void check(String pwd) {
        String err = validate(pwd, PasswordRules.DEFAULT);
        if (err != null) {
            throw BizException.badRequest(err);
        }
    }

    /** 按配置规则校验，不通过直接抛 400。 */
    public static void check(String pwd, PasswordRules rules) {
        String err = validate(pwd, rules);
        if (err != null) {
            throw BizException.badRequest(err);
        }
    }
}
