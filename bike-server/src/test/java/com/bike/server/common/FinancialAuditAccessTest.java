package com.bike.server.common;

import java.util.Arrays;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import static org.assertj.core.api.Assertions.assertThat;

class FinancialAuditAccessTest {
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }

    @ParameterizedTest
    @CsvSource({
        "ROLE_SUPER_ADMIN,true",
        "ROLE_WEB_OPERATOR|PERM_DATA_EXPORT|PERM_PI_READ,false",
        "ROLE_FINANCE|PERM_PI_PAYMENT_CONFIRM,true",
        "ROLE_FINANCE|PERM_PI_RELEASE,true",
        "ROLE_WEB_AGENT|PERM_PI_PAYMENT_CONFIRM,false",
        "ROLE_PDA_OPERATOR|PERM_PI_RELEASE,false"
    })
    void preservesInternalFinancialBoundary(String authorities, boolean expected) {
        var authentication = UsernamePasswordAuthenticationToken.authenticated("operator", null,
                Arrays.stream(authorities.split("\\|")).map(SimpleGrantedAuthority::new).toList());
        SecurityContextHolder.getContext().setAuthentication(authentication);
        assertThat(FinancialAuditAccess.allowed()).isEqualTo(expected);
    }
}
