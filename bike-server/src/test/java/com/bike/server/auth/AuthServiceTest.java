package com.bike.server.auth;

import com.bike.server.common.BusinessException;
import com.bike.server.config.JwtTokenService;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthServiceTest {

    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private final JwtTokenService jwtTokenService = new JwtTokenService(
            "test-only-jwt-secret-key-with-at-least-32-bytes", Duration.ofHours(1));

    @Test
    void loginReturnsBearerTokenForValidCredentials() {
        AuthService service = new AuthServiceImpl(passwordEncoder, jwtTokenService, "admin", "password", false);

        LoginResponse response = service.login(new LoginRequest("admin", "password"));

        assertEquals("admin", response.username());
        assertEquals("Bearer", response.tokenType());
        assertEquals(3600, response.expiresIn());
        assertTrue(response.accessToken().split("\\.").length == 3);
        assertEquals("admin", jwtTokenService.parseUsername(response.accessToken()));
    }

    @Test
    void loginRejectsInvalidCredentialsWithBusinessCode() {
        AuthService service = new AuthServiceImpl(passwordEncoder, jwtTokenService, "admin", "password", false);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.login(new LoginRequest("admin", "wrong-password")));

        assertEquals("AUTH_INVALID_CREDENTIALS", exception.getCode());
    }

    @Test
    void loginLocksAccountAfterRepeatedFailures() {
        AuthService service = new AuthServiceImpl(passwordEncoder, jwtTokenService, "admin", "password", false);

        for (int i = 0; i < 5; i++) {
            assertThrows(BusinessException.class, () -> service.login(new LoginRequest("admin", "wrong-password")));
        }

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.login(new LoginRequest("admin", "password")));
        assertEquals("AUTH_TOO_MANY_ATTEMPTS", exception.getCode());
    }
}
