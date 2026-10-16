package com.g14.g14.login.init;

import com.g14.g14.login.entity.User;
import com.g14.g14.login.enums.RoleEnum;
import com.g14.g14.login.mapper.UserMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 引导管理员初始化器。
 *
 * <p>应用启动时检查管理员账号是否存在，不存在则创建。
 * 幂等：已存在则跳过，不会覆盖管理员已修改的密码。
 *
 * <p>管理员账号由系统预置（{@code must_change_password=0}），
 * 学生 / 老师账号一律由管理员开通并强制首次改密。
 */
@Component
public class AdminInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminInitializer.class);

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;

    @Value("${app.admin.init-account:admin}")
    private String adminAccount;

    @Value("${app.admin.init-password:Admin@123}")
    private String adminPassword;

    public AdminInitializer(UserMapper userMapper, PasswordEncoder passwordEncoder) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (userMapper.countByAccount(adminAccount) > 0) {
            log.info("管理员账号 [{}] 已存在，跳过初始化", adminAccount);
            return;
        }
        User admin = new User();
        admin.setAccount(adminAccount);
        admin.setRealName("系统管理员");
        admin.setRole(RoleEnum.ADMIN);
        admin.setStatus(1);
        admin.setPhone(null);
        admin.setPasswordHash(passwordEncoder.encode(adminPassword));
        admin.setMustChangePassword(0);
        admin.setTokenVersion(0);
        userMapper.insert(admin);

        log.warn("已创建引导管理员账号 [{}]，初始密码 [{}]，请尽快登录修改！", adminAccount, adminPassword);
    }
}
