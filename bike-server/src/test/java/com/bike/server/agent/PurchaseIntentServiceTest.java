package com.bike.server.agent;

import com.bike.server.auth.UserAuthRecord;
import com.bike.server.common.BusinessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PurchaseIntentServiceTest {
    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void agentCreateMayOmitAgentIdBecauseScopeComesFromAuthentication() {
        var authentication = UsernamePasswordAuthenticationToken.authenticated("agent-a", null, List.of(
                new SimpleGrantedAuthority("ROLE_WEB_AGENT"),
                new SimpleGrantedAuthority("PERM_PURCHASE_INTENT_WRITE")));
        authentication.setDetails(new UserAuthRecord(11L, "agent-a", "hash", "AGENT", 101L, null, true, false));
        SecurityContextHolder.getContext().setAuthentication(authentication);
        PurchaseIntentMapper mapper = Mockito.mock(PurchaseIntentMapper.class);
        Mockito.when(mapper.countAuthorizedModel(101L, 1L)).thenReturn(1);
        Mockito.when(mapper.insert(Mockito.any(), Mockito.eq(101L), Mockito.eq(11L))).thenReturn(1);
        Mockito.when(mapper.selectLatestId(101L)).thenReturn(7L);
        PurchaseIntentView view = new PurchaseIntentView(7L, 101L, "Agent A", 1L, "City", 2, "OPEN", null, "test", null);
        Mockito.when(mapper.selectById(7L)).thenReturn(view);

        PurchaseIntentView result = new PurchaseIntentServiceImpl(mapper)
                .create(new PurchaseIntentCreateRequest(null, 1L, 2, null, "test"));

        assertThat(result.agentId()).isEqualTo(101L);
        Mockito.verify(mapper).insert(Mockito.any(), Mockito.eq(101L), Mockito.eq(11L));
    }

    @Test
    void superAdminCreateRequiresAgentId() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "admin", null, List.of(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"))));
        PurchaseIntentMapper mapper = Mockito.mock(PurchaseIntentMapper.class);

        assertThatThrownBy(() -> new PurchaseIntentServiceImpl(mapper)
                .create(new PurchaseIntentCreateRequest(null, 1L, 1, null, null)))
                .isInstanceOf(BusinessException.class)
                .extracting(BusinessException.class::cast)
                .extracting(BusinessException::getCode)
                .isEqualTo("AGENT_REQUIRED");
        Mockito.verifyNoInteractions(mapper);
    }
}
