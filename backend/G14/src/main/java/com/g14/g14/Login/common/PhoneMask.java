package com.g14.g14.login.common;

/**
 * 手机号脱敏。
 *
 * <p>接口返回的手机号一律脱敏为 {@code 138****0001} 形式。
 * 前端契约测试以 {@code /^\d{3}\*{4}\d{4}$/} 断言该格式。
 *
 * <p>注意：脱敏只发生在「输出」环节，数据库仍存完整号码（登录时按完整号码匹配）。
 */
public final class PhoneMask {

    private PhoneMask() {
    }

    /**
     * 脱敏：保留前 3 位与后 4 位，中间用 4 个星号替代。
     * 长度不足以脱敏时原样返回，避免产生误导性的残缺号码。
     */
    public static String mask(String phone) {
        if (phone == null) {
            return null;
        }
        String trimmed = phone.trim();
        if (trimmed.length() < 7) {
            return trimmed;
        }
        return trimmed.substring(0, 3) + "****" + trimmed.substring(trimmed.length() - 4);
    }
}
