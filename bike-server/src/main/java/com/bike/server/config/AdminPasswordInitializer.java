package com.bike.server.config;

import com.bike.server.auth.UserAuthRecord;
import com.bike.server.system.UserMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import jakarta.annotation.PostConstruct;

/**
 * 将部署配置中的管理员密码写入仍处于首次登录状态的管理员账号。
 * 已完成改密的账号不会被启动过程覆盖。
 */
@Component
public class AdminPasswordInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger(AdminPasswordInitializer.class);

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final String adminUsername;
    private final String configuredPassword;
    private final boolean configuredPasswordIsHash;
    private final String flywayLocations;

    public AdminPasswordInitializer(
            UserMapper userMapper,
            PasswordEncoder passwordEncoder,
            @Value("${bike.security.admin-username:admin}") String adminUsername,
            @Value("${bike.security.admin-password:}") String configuredPassword,
            @Value("${bike.security.admin-password-is-hash:false}") boolean configuredPasswordIsHash,
            @Value("${spring.flyway.locations:classpath:db/migration}") String flywayLocations) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.adminUsername = adminUsername;
        this.configuredPassword = configuredPassword;
        this.configuredPasswordIsHash = configuredPasswordIsHash;
        this.flywayLocations = flywayLocations;
    }

    @PostConstruct
    void validateProductionPasswordConfiguration() {
        if (flywayLocations.contains("migration-prod") && (configuredPassword == null || configuredPassword.isBlank())) {
            throw new IllegalStateException("生产迁移目录必须同时配置 BIKE_ADMIN_PASSWORD");
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void initialize() {
        if (configuredPassword == null || configuredPassword.isBlank()) {
            return;
        }
        UserAuthRecord user = userMapper.selectAuthByUsername(adminUsername);
        if (user == null || !user.enabled() || !user.mustChangePassword()) {
            return;
        }
        String passwordHash = configuredPasswordIsHash ? configuredPassword : passwordEncoder.encode(configuredPassword);
        int updated = userMapper.updatePasswordForFirstLogin(adminUsername, passwordHash);
        if (updated == 1) {
            LOGGER.info("已根据部署配置初始化管理员账号密码，username={}", adminUsername);
        }
    }
}
