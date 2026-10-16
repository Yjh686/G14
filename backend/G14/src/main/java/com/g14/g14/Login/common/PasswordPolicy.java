package com.g14.g14.login.common;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 密码强度策略（集中管理，避免散落在各处）。
 *
 * <p>两道关卡：
 * <ol>
 *   <li><b>复杂度</b>：长度 8~32，必须同时包含字母与数字，不允许空白字符；</li>
 *   <li><b>弱口令黑名单</b>：拦截常见口令与系统初始密码。
 *       仅靠复杂度规则无法拦住 {@code Init@1234} 这类「看似合规」的初始密码，
 *       因此必须叠加黑名单，否则用户可以把密码改回初始密码、强制改密形同虚设。</li>
 * </ol>
 *
 * <p>注意：本策略只作用于「用户自己设置的新密码」以及「管理员显式指定的密码」。
 * 管理员不指定密码时，后端使用配置项 {@code app.auth.initial-password}，
 * 走的是「只校验长度」的宽松分支 —— 因为初始密码本身就在黑名单里，
 * 若套用完整策略会自相矛盾（见 tools/demo-fixture.mjs 的注释）。
 */
public final class PasswordPolicy {

    private static final int MIN_LEN = 8;
    private static final int MAX_LEN = 32;

    private static final Pattern LETTER = Pattern.compile("[A-Za-z]");
    private static final Pattern DIGIT = Pattern.compile("\\d");
    private static final Pattern BLANK = Pattern.compile("\\s");

    /** 弱口令黑名单（统一小写比较）。含系统初始密码 Init@1234。 */
    private static final Set<String> BLACKLIST = new HashSet<>(Arrays.asList(
            "123456", "1234567", "12345678", "123456789", "1234567890",
            "11111111", "00000000", "88888888",
            "password", "password1", "passw0rd", "qwerty123", "abc12345",
            "a1234567", "abcd1234", "admin123", "admin@123", "root1234",
            "iloveyou", "letmein123",
            "init@1234"
    ));

    private PasswordPolicy() {
    }

    /**
     * 完整策略校验（复杂度 + 黑名单），不符合直接抛 {@link BizException}。
     *
     * @param rawPassword 明文密码
     */
    public static void validate(String rawPassword) {
        checkComplexity(rawPassword);
        if (BLACKLIST.contains(rawPassword.toLowerCase(Locale.ROOT))) {
            throw new BizException(ResultCode.PASSWORD_INVALID, "密码过于简单，属于常见弱口令");
        }
    }

    /**
     * 宽松校验：仅检查长度与空白字符。
     * 用于系统初始密码这类「不由用户选择」的场景。
     */
    public static void validateLengthOnly(String rawPassword) {
        if (rawPassword == null || rawPassword.trim().isEmpty()) {
            throw new BizException(ResultCode.PASSWORD_INVALID, "密码不能为空");
        }
        if (rawPassword.length() < MIN_LEN || rawPassword.length() > MAX_LEN) {
            throw new BizException(ResultCode.PASSWORD_INVALID,
                    "密码长度必须为 " + MIN_LEN + "~" + MAX_LEN + " 位");
        }
        if (BLANK.matcher(rawPassword).find()) {
            throw new BizException(ResultCode.PASSWORD_INVALID, "密码不允许包含空格");
        }
    }

    private static void checkComplexity(String rawPassword) {
        if (rawPassword == null || rawPassword.trim().isEmpty()) {
            throw new BizException(ResultCode.PASSWORD_INVALID, "密码不能为空");
        }
        if (rawPassword.length() < MIN_LEN || rawPassword.length() > MAX_LEN) {
            throw new BizException(ResultCode.PASSWORD_INVALID,
                    "密码长度必须为 " + MIN_LEN + "~" + MAX_LEN + " 位");
        }
        if (BLANK.matcher(rawPassword).find()) {
            throw new BizException(ResultCode.PASSWORD_INVALID, "密码不允许包含空格");
        }
        if (!LETTER.matcher(rawPassword).find() || !DIGIT.matcher(rawPassword).find()) {
            throw new BizException(ResultCode.PASSWORD_INVALID, "密码必须同时包含字母和数字");
        }
    }

    public static int getMinLength() {
        return MIN_LEN;
    }
}
