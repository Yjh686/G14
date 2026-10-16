package com.g14.g14.login.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 已登出 Token 黑名单。
 *
 * <p>JWT 是无状态的，签发后无法撤回。为支持「登出即失效」，
 * 将登出 Token 的 jti 记入黑名单，拦截器每次校验时比对。
 *
 * <p><b>实现取舍</b>：当前使用进程内 ConcurrentHashMap，满足单机部署与课程设计场景。
 * 其局限是重启后失效、多实例不共享。生产环境应替换为 Redis：
 * {@code SETEX blacklist:{jti} <剩余秒数> 1}，并把本类抽象为接口以支持替换。
 */
@Component
public class TokenBlacklist {

    private static final Logger log = LoggerFactory.getLogger(TokenBlacklist.class);

    /** jti -> Token 到期时间戳（毫秒）。到期后条目自动失去意义，可安全清理。 */
    private final Map<String, Long> store = new ConcurrentHashMap<>();

    /**
     * 加入黑名单。
     *
     * @param jti          Token 唯一 ID
     * @param expireAtMillis Token 自身到期时间，早于当前时间的 Token 无需记录
     */
    public void add(String jti, long expireAtMillis) {
        if (jti == null) {
            return;
        }
        if (expireAtMillis <= System.currentTimeMillis()) {
            // Token 本身已过期，解析阶段就会被拒绝，无需占用内存
            return;
        }
        store.put(jti, expireAtMillis);
    }

    public boolean contains(String jti) {
        if (jti == null) {
            return false;
        }
        Long expireAt = store.get(jti);
        if (expireAt == null) {
            return false;
        }
        if (expireAt <= System.currentTimeMillis()) {
            store.remove(jti);
            return false;
        }
        return true;
    }

    /**
     * 定时清理已过期条目，防止内存无限增长。每 10 分钟执行一次。
     */
    @Scheduled(fixedDelay = 600_000L)
    public void evictExpired() {
        long now = System.currentTimeMillis();
        int before = store.size();
        store.entrySet().removeIf(e -> e.getValue() <= now);
        int removed = before - store.size();
        if (removed > 0) {
            log.debug("黑名单清理完成，移除 {} 条，剩余 {} 条", removed, store.size());
        }
    }

    public int size() {
        return store.size();
    }
}
