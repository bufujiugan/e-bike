package com.bike.server.common;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CurrentOperatorTest {
    @AfterEach
    void clearAuthentication() {
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }

    @Test
    void permissionChecksRejectMissingAuthentication() {
        assertThatThrownBy(() -> CurrentOperator.requirePermission("VEHICLE_READ"))
                .isInstanceOf(BusinessException.class)
                .extracting(BusinessException.class::cast)
                .extracting(BusinessException::getCode)
                .isEqualTo("UNAUTHORIZED");
        assertThatThrownBy(() -> CurrentOperator.requireAnyPermission("VEHICLE_READ"))
                .isInstanceOf(BusinessException.class)
                .extracting(BusinessException.class::cast)
                .extracting(BusinessException::getCode)
                .isEqualTo("UNAUTHORIZED");
        assertThatThrownBy(() -> CurrentOperator.requireWorkstation("ASSEMBLY"))
                .isInstanceOf(BusinessException.class)
                .extracting(BusinessException.class::cast)
                .extracting(BusinessException::getCode)
                .isEqualTo("UNAUTHORIZED");
    }

    @Test
    void anyRoleCheckAcceptsOneOfTheConfiguredRoles() {
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                org.springframework.security.authentication.UsernamePasswordAuthenticationToken.authenticated(
                        "operator", null, java.util.List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_SUPER_ADMIN"))));

        CurrentOperator.requireAnyRole("WEB_AGENT", "SUPER_ADMIN");
    }
}
