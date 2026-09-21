package com.bike.server.auth;

import com.bike.server.common.BusinessException;
import com.bike.server.config.JwtTokenService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import com.bike.server.system.UserMapper;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

@Service
public class AuthServiceImpl implements AuthService {

    private final PasswordEncoder passwordEncoder;
    private final JwtTokenService jwtTokenService;
    private final LoginLogMapper loginLogMapper;
    private final UserMapper userMapper;
    private final String adminUsername;
    private volatile String adminPasswordHash;
    private final ConcurrentHashMap<String, LoginAttempt> failedAttempts = new ConcurrentHashMap<>();
    private static final int MAX_FAILURES = 5;
    private static final long LOCK_SECONDS = 15 * 60;
    @Autowired(required = false)
    private TokenRevocationMapper revocations;

    @Autowired
    public AuthServiceImpl(
            PasswordEncoder passwordEncoder,
            JwtTokenService jwtTokenService,
            LoginLogMapper loginLogMapper,
            UserMapper userMapper,
            @Value("${bike.security.admin-username:admin}") String adminUsername,
            @Value("${bike.security.admin-password:}") String adminPassword,
            @Value("${bike.security.admin-password-is-hash:false}") boolean adminPasswordIsHash) {
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenService = jwtTokenService;
        this.loginLogMapper = loginLogMapper;
        this.userMapper = userMapper;
        this.adminUsername = adminUsername;
        this.adminPasswordHash = adminPassword == null || adminPassword.isBlank()
                ? null : (adminPasswordIsHash ? adminPassword : passwordEncoder.encode(adminPassword));
    }

    /** 保留原有构造器，供既有单元测试和兼容调用方使用。 */
    public AuthServiceImpl(PasswordEncoder passwordEncoder, JwtTokenService jwtTokenService,
                           String adminUsername, String adminPassword, boolean adminPasswordIsHash) {
        this(passwordEncoder, jwtTokenService, null, null, adminUsername, adminPassword, adminPasswordIsHash);
    }

    @Override
    public LoginResponse login(LoginRequest request, String clientIp) {
        LoginAttempt attempt = failedAttempts.get(request.username());
        if (attempt != null && attempt.lockedUntil().isAfter(Instant.now())) {
            throw new BusinessException("AUTH_TOO_MANY_ATTEMPTS", "登录失败次数过多，请稍后再试");
        }
        UserAuthRecord databaseUser = userMapper == null ? null : userMapper.selectAuthByUsername(request.username());
        boolean databaseValid = databaseUser != null && databaseUser.enabled()
                && passwordEncoder.matches(request.password(), databaseUser.passwordHash());
        // 配置账号只供无数据库的兼容测试使用；正式运行必须由数据库确认账号。
        boolean configValid = userMapper == null && adminPasswordHash != null && adminUsername.equals(request.username())
                && passwordEncoder.matches(request.password(), adminPasswordHash);
        if (!databaseValid && !configValid) {
            if (loginLogMapper != null) loginLogMapper.insert(request.username(), false, normalizeClientIp(clientIp), "用户名或密码错误");
            failedAttempts.compute(request.username(), (key, previous) -> {
                int failures = previous == null ? 1 : previous.failures() + 1;
                return new LoginAttempt(failures, failures >= MAX_FAILURES ? Instant.now().plusSeconds(LOCK_SECONDS) : Instant.EPOCH);
            });
            throw new BusinessException("AUTH_INVALID_CREDENTIALS", "用户名或密码错误");
        }
        failedAttempts.remove(request.username());
        if (databaseValid) userMapper.updateLastLogin(databaseUser.username());
        if (loginLogMapper != null) loginLogMapper.insert(request.username(), true, normalizeClientIp(clientIp), null);
        JwtTokenService.Token token = jwtTokenService.issue(request.username(), databaseValid ? databaseUser.authVersion() : 0);
        return new LoginResponse(token.value(), "Bearer", token.expiresInSeconds(), request.username(),
                databaseValid && databaseUser.mustChangePassword());
    }

    @Override
    public LoginResponse me(String username, String token) {
        var identity = jwtTokenService.parse(token);
        UserAuthRecord user = userMapper == null ? null : userMapper.selectAuthByUsername(username);
        if (!username.equals(identity.username()) || user == null || !user.enabled()
                || user.authVersion() != identity.authVersion()
                || (revocations != null && revocations.countRevoked(identity.id()) > 0))
            throw new BusinessException("UNAUTHORIZED", "登录凭证已失效");
        // 查询当前身份不续发凭证，避免退出前派生出仍有效的其他凭证。
        return new LoginResponse(token, "Bearer", Math.max(0, java.time.Duration.between(Instant.now(), identity.expiresAt()).toSeconds()), username,
                user != null && user.mustChangePassword());
    }

    @Override
    public void logout(String token) {
        var identity = jwtTokenService.parse(token);
        if (revocations == null) throw new IllegalStateException("登录凭证撤销存储不可用");
        revocations.revoke(identity.id(), identity.expiresAt());
    }

    @Override
    public AuthContextResponse context(String username) {
        UserAuthRecord user = userMapper == null ? null : userMapper.selectAuthByUsername(username);
        if (userMapper == null) {
            return new AuthContextResponse(username, "WEB_ADMIN", null,
                    java.util.List.of("SUPER_ADMIN"), java.util.List.of("*"), java.util.List.of("*"), false);
        }
        if (user == null || !user.enabled()) throw new BusinessException("UNAUTHORIZED", "账号不存在或已停用");
        var roles = new java.util.ArrayList<>(userMapper.selectRoleCodes(username));
        if (roles.isEmpty()) throw new BusinessException("PERMISSION_DENIED", "账号没有启用的角色");
        if ("AGENT".equals(user.userType()) && !roles.contains("WEB_AGENT")) roles.add("WEB_AGENT");
        if ("PDA_OPERATOR".equals(user.userType()) && !roles.contains("PDA_OPERATOR")) roles.add("PDA_OPERATOR");
        return new AuthContextResponse(username, user.userType(), user.agentId(),
                roles, userMapper.selectPermissionCodes(username),
                userMapper.selectWorkstationTypes(username), user.mustChangePassword());
    }

    @Override
    public void changePassword(ChangePasswordRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String username = authentication == null ? adminUsername : authentication.getName();
        UserAuthRecord databaseUser = userMapper == null ? null : userMapper.selectAuthByUsername(username);
        if (databaseUser != null) {
            if (!passwordEncoder.matches(request.currentPassword(), databaseUser.passwordHash())) throw new BusinessException("AUTH_INVALID_CREDENTIALS", "当前密码错误");
            if (userMapper.updatePassword(username, passwordEncoder.encode(request.newPassword())) != 1) throw new BusinessException("PASSWORD_UPDATE_FAILED", "密码修改失败");
            return;
        }
        if (adminPasswordHash == null || !passwordEncoder.matches(request.currentPassword(), adminPasswordHash)) throw new BusinessException("AUTH_INVALID_CREDENTIALS", "当前密码错误");
        adminPasswordHash = passwordEncoder.encode(request.newPassword());
    }

    private record LoginAttempt(int failures, Instant lockedUntil) { }

    private static String normalizeClientIp(String clientIp) {
        if (clientIp == null || clientIp.isBlank()) return null;
        String value = clientIp.trim();
        return value.length() > 64 ? value.substring(0, 64) : value;
    }
}
