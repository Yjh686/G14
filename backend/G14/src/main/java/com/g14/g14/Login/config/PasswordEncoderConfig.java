package com.g14.g14.login.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 密码加密器配置。
 *
 * <p>选用 BCrypt 的理由（需求 8）：
 * <ul>
 *   <li>自带随机盐，同一明文每次哈希结果不同，杜绝彩虹表；</li>
 *   <li>计算强度可调（cost 因子），默认 10 约 50~100ms/次，暴力破解成本高；</li>
 *   <li>算法本身设计为「慢哈希」，天然抵抗离线爆破。</li>
 * </ul>
 *
 * <p>数据库中存储的是 60 字符的 {@code $2a$10$...} 字符串，校验使用
 * {@link PasswordEncoder#matches(CharSequence, String)}（内部为定长比较，抗计时攻击）。
 */
@Configuration
public class PasswordEncoderConfig {

    /** 计算强度：10 → 2^10 次迭代。生产环境可提升到 12。 */
    private static final int STRENGTH = 10;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(STRENGTH);
    }
}
