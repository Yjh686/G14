package com.yjh.backend1.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * JWT 生成与解析。
 * <p>
 * 令牌载荷刻意做得很薄：{@code sub=userId}、{@code username}、{@code ver=tokenVersion}。
 * <b>不把角色/权限塞进令牌</b>，原因：
 * <ul>
 *   <li>权限变更要立即生效，塞进令牌就得等过期</li>
 *   <li>令牌体积小，前端存 localStorage 不易超限</li>
 * </ul>
 * 代价是每个请求要查一次用户（本地缓存兜底），换来的正确性更高。
 */
@Slf4j
@Component
public class JwtUtil {

    private final SecretKey key;
    private final long expireMillis;

    public JwtUtil(@Value("${app.jwt.secret}") String secret,
                   @Value("${app.jwt.expire-minutes}") long expireMinutes) {
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            throw new IllegalStateException("app.jwt.secret 长度不足 32 字节，HS256 不安全");
        }
        this.key = Keys.hmacShaKeyFor(bytes);
        this.expireMillis = expireMinutes * 60_000L;
    }

    /**
     * 生成令牌。
     *
     * @param userId       用户 ID
     * @param username     账号
     * @param tokenVersion 用户当前 token_version，用于使旧令牌失效
     */
    public String generate(Long userId, String username, int tokenVersion) {
        Date now = new Date();
        return Jwts.builder()
                .setSubject(String.valueOf(userId))
                .claim("username", username)
                .claim("ver", tokenVersion)
                .setIssuedAt(now)
                .setExpiration(new Date(now.getTime() + expireMillis))
                .signWith(key, SignatureAlgorithm.HS256)
                .compact();
    }

    /**
     * 解析并校验签名与过期时间。
     *
     * @return 解析结果；令牌非法/过期返回 null（由调用方决定抛 401）
     */
    public Claims parse(String token) {
        try {
            return Jwts.parserBuilder()
                    .setSigningKey(key)
                    .build()
                    .parseClaimsJws(token)
                    .getBody();
        } catch (JwtException | IllegalArgumentException e) {
            log.debug("JWT 解析失败: {}", e.getMessage());
            return null;
        }
    }

    /** 从令牌取 userId，解析失败返回 null */
    public Long extractUserId(String token) {
        Claims c = parse(token);
        if (c == null) {
            return null;
        }
        try {
            return Long.valueOf(c.getSubject());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 从令牌取 token_version，解析失败返回 -1 */
    public int extractTokenVersion(String token) {
        Claims c = parse(token);
        if (c == null) {
            return -1;
        }
        Object v = c.get("ver");
        return v instanceof Number n ? n.intValue() : -1;
    }

    public long getExpireSeconds() {
        return expireMillis / 1000;
    }
}
