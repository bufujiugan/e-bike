package com.bike.server.agent;

import com.bike.server.common.*;
import java.util.List;
import org.junit.jupiter.api.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentModelAccessServiceTest {
    final AgentModelAccessMapper mapper=mock(AgentModelAccessMapper.class);
    final OperationLogMapper logs=mock(OperationLogMapper.class);
    final AgentModelAccessService service=new AgentModelAccessService(mapper,logs);
    @BeforeEach void admin(){SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("admin",null,List.of(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"))));}
    @AfterEach void clear(){SecurityContextHolder.clearContext();}
    @Test void emptySelectionRevokesAndKeepsAudit(){
        when(mapper.lockAgent(7)).thenReturn(7L);when(mapper.selectModels(7)).thenReturn(List.of(27L),List.of());
        assertThat(service.replace(7,List.of())).isEmpty();verify(mapper).disableAll(7);verify(mapper,never()).grant(anyLong(),anyLong());
        verify(logs).insert(eq("PERMISSION_CHANGE"),eq("7"),anyString(),any(),eq("admin"),eq("{\"modelIds\":[27]}"),eq("{\"modelIds\":[]}"),any(),any());
    }
    @Test void invalidModelsDoNotRemovePreviousGrants(){
        when(mapper.lockAgent(7)).thenReturn(7L);
        assertThatThrownBy(()->service.replace(7,List.of(99L))).isInstanceOf(BusinessException.class);
        verify(mapper,never()).disableAll(anyLong());
    }
    @Test void agentCannotGrantItselfModels(){
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("agent",null,List.of(new SimpleGrantedAuthority("ROLE_WEB_AGENT"))));
        assertThatThrownBy(()->service.replace(7,List.of(27L))).isInstanceOf(BusinessException.class);verifyNoInteractions(mapper);
    }
}
