package com.g14.g14.login.security;

import com.g14.g14.login.common.BizException;
import com.g14.g14.login.common.ResultCode;
import com.g14.g14.login.entity.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.util.Date;
import java.util.UUID;

/**
 * JWT 签发与校验工具（HS256 对称签名）。
 *
 * <p>载荷设计 —— 只放鉴权必需的<b>非敏感</b>字段，姓名 / 手机号等一律通过
 * {@code GET /api/auth/me} 获取：
 * <pre>
 * {
 *   "jti": "a1b2c3...",     // 唯一 ID，登出时写入黑名单
 *   "sub": "1",             // 用户 ID（需求 3：token 含用户 ID）
 *   "iss": "g14-questionnaire",
 *   "iat": 1730000000,
 *   "exp": 1730007200,
 *   "role": "STUDENT",      // 角色（需求 3：token 含角色）
 *   "tv": 0                 // tokenVersion，改密 / 重置 / 禁用后旧令牌自动失效
 * }
 * </pre>
 *
 * <p>载荷仅 Base64Url 编码、未加密，因此<b>不放任何敏感信息</b>；
 * 服务端也不信任载荷里的 role，实际鉴权以数据库中的实时角色为准。
 */
@Component
public class JwtUtil {

    private static final Logger log = LoggerFactory.getLogger(JwtUtil.class);

    public static final String HEADER_NAME = "Authorization";
    public static final String TOKEN_PREFIX = "Bearer ";
    public static final String TOKEN_TYPE = "Bearer";

    public static final String CLAIM_ROLE = "role";
    public static final String CLAIM_TOKEN_VERSION = "tv";

    /** HS256 要求密钥不少于 256 bit（32 字节）。 */
    private static final int MIN_SECRET_BYTES = 32;

    @Value("${app.jwt.secret}")
    private String secret;

    @Value("${app.jwt.issuer}")
    private String issuer;

    @Value("${app.jwt.expire-seconds:7200}")
    private long expireSeconds;

    private Key signingKey;

    @PostConstruct
    public void init() {
        byte[] keyBytes = secret == null ? new byte[0] : secret.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "app.jwt.secret 长度不足 " + MIN_SECRET_BYTES + " 字节，无法满足 HS256 安全强度要求");
        }
        this.signingKey = Keys.hmacShaKeyFor(keyBytes);
        log.info("JWT 初始化完成：issuer={}，有效期={}s", issuer, expireSeconds);
    }

    /** 签发 Token：写入用户 ID（sub）与角色（role）。 */
    public String generate(User user) {
        Date now = new Date();
        Date expiration = new Date(now.getTime() + expireSeconds * 1000L);
        return Jwts.builder()
                .setId(UUID.randomUUID().toString().replace("-", ""))
                .setSubject(String.valueOf(user.getId()))
                .setIssuer(issuer)
                .setIssuedAt(now)
                .setExpiration(expiration)
                .claim(CLAIM_ROLE, user.getRole() == null ? null : user.getRole().name())
                .claim(CLAIM_TOKEN_VERSION, user.getTokenVersion() == null ? 0 : user.getTokenVersion())
                .signWith(signingKey, SignatureAlgorithm.HS256)
                .compact();
    }

    /**
     * 解析并校验 Token（签名 + 签发方 + 有效期）。
     * <p>过期 → 40100；格式 / 签名错误 → 40102。两者都是 HTTP 401。
     */
    public Claims parse(String token) {
        try {
            return Jwts.parserBuilder()
                    .setSigningKey(signingKey)
                    .requireIssuer(issuer)
                    .build()
                    .parseClaimsJws(token)
                    .getBody();
        } catch (ExpiredJwtException e) {
            throw new BizException(ResultCode.UNAUTHORIZED, "登录已过期，请重新登录");
        } catch (JwtException | IllegalArgumentException e) {
            log.debug("Token 解析失败: {}", e.getMessage());
            throw new BizException(ResultCode.TOKEN_INVALID);
        }
    }

    public Long getUserId(Claims claims) {
        return Long.valueOf(claims.getSubject());
    }

    public String getRole(Claims claims) {
        return claims.get(CLAIM_ROLE, String.class);
    }

    public int getTokenVersion(Claims claims) {
        Object v = claims.get(CLAIM_TOKEN_VERSION);
        return v == null ? 0 : ((Number) v).intValue();
    }

    /** Token 到期时间戳（毫秒），登出时用于计算黑名单条目的存活时间。 */
    public long getExpireAtMillis(Claims claims) {
        return claims.getExpiration().getTime();
    }

    public long getExpireSeconds() {
        return expireSeconds;
    }
}
