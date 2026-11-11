package com.yjh.backend1.common;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 请求来源信息提取。
 * <p>
 * 只取 {@code X-Forwarded-For} 的第一段，并限制长度，
 * 因为该头可被客户端伪造，绝不能用于任何安全判断，仅作日志排查线索。
 */
public final class RequestUtil {

    private static final int MAX_LEN = 64;

    private RequestUtil() {
    }

    public static String clientIp(HttpServletRequest req) {
        if (req == null) {
            return null;
        }
        String ip = req.getHeader("X-Forwarded-For");
        if (ip != null && !ip.isBlank() && !"unknown".equalsIgnoreCase(ip)) {
            int comma = ip.indexOf(',');
            if (comma > 0) {
                ip = ip.substring(0, comma);
            }
            ip = ip.trim();
        } else {
            ip = req.getHeader("X-Real-IP");
            if (ip == null || ip.isBlank()) {
                ip = req.getRemoteAddr();
            }
        }
        if (ip == null) {
            return null;
        }
        return ip.length() > MAX_LEN ? ip.substring(0, MAX_LEN) : ip;
    }

    public static String userAgent(HttpServletRequest req) {
        if (req == null) {
            return null;
        }
        String ua = req.getHeader("User-Agent");
        if (ua == null) {
            return null;
        }
        return ua.length() > 255 ? ua.substring(0, 255) : ua;
    }
}
