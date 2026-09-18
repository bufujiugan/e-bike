package com.bike.server.system;

import com.bike.server.common.BusinessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

class UserServiceTest {
    @BeforeEach
    void setAuthenticatedAdmin() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("admin", "N/A",
                        java.util.List.of(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"))));
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void createRejectsAgentAccountWithoutAgentBinding() {
        UserMapper mapper = Mockito.mock(UserMapper.class);

        assertThatThrownBy(() -> new UserServiceImpl(mapper, new BCryptPasswordEncoder())
                .create(new UserCreateRequest("agent-a", "password1", "代理商 A", "AGENT", null, null)))
                .isInstanceOf(BusinessException.class)
                .extracting(BusinessException.class::cast)
                .extracting(BusinessException::getCode)
                .isEqualTo("AGENT_REQUIRED");
        verify(mapper, never()).insert(any(), any());
    }

    @Test
    void createRejectsPdaAccountWithDisabledWorkstation() {
        UserMapper mapper = Mockito.mock(UserMapper.class);
        when(mapper.countEnabledWorkstation(9L)).thenReturn(0);

        assertThatThrownBy(() -> new UserServiceImpl(mapper, new BCryptPasswordEncoder())
                .create(new UserCreateRequest("pda-a", "password1", "入库员", "PDA_OPERATOR", null, 9L)))
                .isInstanceOf(BusinessException.class)
                .extracting(BusinessException.class::cast)
                .extracting(BusinessException::getCode)
                .isEqualTo("WORKSTATION_NOT_FOUND");
        verify(mapper, never()).insert(any(), any());
    }

    @Test
    void updatePreservesExistingUserTypeWhenRequestOmitsIt() {
        UserMapper mapper = Mockito.mock(UserMapper.class);
        UserView before = new UserView();
        before.setId(7L);
        before.setUserType("WEB_OPERATOR");
        before.setDisplayName("旧名称");
        UserView after = new UserView();
        after.setId(7L);
        after.setUserType("WEB_OPERATOR");
        after.setDisplayName("新名称");
        when(mapper.selectById(7L)).thenReturn(before, after);
        when(mapper.updateProfile(eq(7L), any(UserUpdateRequest.class))).thenReturn(1);

        new UserServiceImpl(mapper, new BCryptPasswordEncoder())
                .update(7L, new UserUpdateRequest("新名称", null, null, null));

        ArgumentCaptor<UserUpdateRequest> captor = ArgumentCaptor.forClass(UserUpdateRequest.class);
        verify(mapper).updateProfile(eq(7L), captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue().userType()).isEqualTo("WEB_OPERATOR");
    }
}
