package com.bike.server.config;

import com.bike.server.auth.UserAuthRecord;
import com.bike.server.system.UserMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class JwtAuthenticationFilterTest {
    private final UserMapper users = mock(UserMapper.class);
    private final JwtTokenService tokens = new JwtTokenService(
            "test-only-jwt-secret-key-with-at-least-32-bytes", Duration.ofHours(1));

    @AfterEach void clear() { SecurityContextHolder.clearContext(); }

    private void authenticate(String username) throws Exception {
        var request = new MockHttpServletRequest("GET", "/api/vehicles");
        request.addHeader("Authorization", "Bearer " + tokens.issue(username).value());
        new JwtAuthenticationFilter(tokens, users).doFilter(request, new MockHttpServletResponse(), (req, res) -> {});
    }

    @Test void removingAllRolesDoesNotRestoreAdministratorAccess() throws Exception {
        when(users.selectAuthByUsername("manager")).thenReturn(
                new UserAuthRecord(2, "manager", "hash", "WEB_ADMIN", null, null, true));
        when(users.selectRoleCodes("manager")).thenReturn(List.of());
        authenticate("manager");
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test void administratorNameDoesNotBypassRevokedRoles() throws Exception {
        when(users.selectAuthByUsername("admin")).thenReturn(
                new UserAuthRecord(1, "admin", "hash", "WEB_ADMIN", null, null, true));
        authenticate("admin");
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test void deletedAccountCannotReusePreviouslyIssuedToken() throws Exception {
        authenticate("deleted");
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test void explicitAdministratorRoleStillWorks() throws Exception {
        when(users.selectAuthByUsername("admin")).thenReturn(
                new UserAuthRecord(1, "admin", "hash", "WEB_ADMIN", null, null, true));
        when(users.selectRoleCodes("admin")).thenReturn(List.of("SUPER_ADMIN"));
        authenticate("admin");
        assertThat(SecurityContextHolder.getContext().getAuthentication().getAuthorities())
                .extracting("authority").contains("ROLE_SUPER_ADMIN");
    }

    @Test void customAgentRoleRetainsAgentDataIsolation() throws Exception {
        when(users.selectAuthByUsername("dealer")).thenReturn(
                new UserAuthRecord(3, "dealer", "hash", "AGENT", 7L, null, true));
        when(users.selectRoleCodes("dealer")).thenReturn(List.of("DEALER_VIEWER"));
        when(users.selectPermissionCodes("dealer")).thenReturn(List.of("ORDER_READ"));
        authenticate("dealer");
        assertThat(SecurityContextHolder.getContext().getAuthentication().getAuthorities())
                .extracting("authority").contains("ROLE_WEB_AGENT", "PERM_ORDER_READ")
                .doesNotContain("ROLE_SUPER_ADMIN");
    }
}
