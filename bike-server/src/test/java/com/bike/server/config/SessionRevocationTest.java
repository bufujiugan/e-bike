package com.bike.server.config;

import com.bike.server.auth.*;
import com.bike.server.system.UserMapper;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SessionRevocationTest {
    final UserMapper users = mock(UserMapper.class);
    final TokenRevocationMapper revoked = mock(TokenRevocationMapper.class);
    final JwtTokenService tokens = new JwtTokenService("test-only-jwt-secret-key-with-at-least-32-bytes", Duration.ofHours(1));
    final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);
    final AuthServiceImpl auth = new AuthServiceImpl(encoder, tokens, null, users, "admin", "", false);
    final java.util.Set<String> revokedIds = ConcurrentHashMap.newKeySet();
    UserAuthRecord current = new UserAuthRecord(2, "worker", encoder.encode("old-password"), "WEB_OPERATOR", null, null, true, false, 0);

    SessionRevocationTest() {
        ReflectionTestUtils.setField(auth, "revocations", revoked);
        when(users.selectAuthByUsername("worker")).thenAnswer(i -> current);
        when(users.selectRoleCodes("worker")).thenReturn(List.of("WORKER"));
        when(revoked.countRevoked(anyString())).thenAnswer(i -> revokedIds.contains(i.getArgument(0)) ? 1 : 0);
        when(revoked.revoke(anyString(), any())).thenAnswer(i -> { revokedIds.add(i.getArgument(0)); return 1; });
        when(users.updatePassword(eq("worker"), anyString())).thenAnswer(i -> {
            current = new UserAuthRecord(2, "worker", i.getArgument(1), "WEB_OPERATOR", null, null, true, false, current.authVersion()+1);
            return 1;
        });
    }
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }
    boolean accepts(String token) throws Exception {
        SecurityContextHolder.clearContext();
        var request = new MockHttpServletRequest("GET", "/api/auth/me");
        request.addHeader("Authorization", "Bearer " + token);
        new JwtAuthenticationFilter(tokens, users, revoked).doFilter(request, new MockHttpServletResponse(), (r,s)->{});
        return SecurityContextHolder.getContext().getAuthentication() != null;
    }
    @Test void logoutRevokesOnlyThatLoginAndMeCannotCreateAnUnrevokedCopy() throws Exception {
        String first = auth.login(new LoginRequest("worker", "old-password")).accessToken();
        String second = auth.login(new LoginRequest("worker", "old-password")).accessToken();
        assertThat(second).isNotEqualTo(first);
        assertThat(auth.me("worker", first).accessToken()).isEqualTo(first);
        auth.logout(first);
        assertThat(accepts(first)).isFalse();
        assertThatThrownBy(() -> auth.me("worker", first)).isInstanceOf(com.bike.server.common.BusinessException.class);
        assertThat(accepts(second)).isTrue();
    }
    @Test void passwordChangeRejectsAllOldSessionsIncludingSameSecondTokens() throws Exception {
        String first = auth.login(new LoginRequest("worker", "old-password")).accessToken();
        String second = auth.login(new LoginRequest("worker", "old-password")).accessToken();
        assertThat(accepts(first)).isTrue();
        auth.changePassword(new ChangePasswordRequest("old-password", "new-password"));
        assertThat(accepts(first)).isFalse();
        assertThat(accepts(second)).isFalse();
        assertThatThrownBy(() -> auth.login(new LoginRequest("worker", "old-password"))).isInstanceOf(com.bike.server.common.BusinessException.class);
        assertThat(accepts(auth.login(new LoginRequest("worker", "new-password")).accessToken())).isTrue();
    }
}
