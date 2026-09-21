package com.bike.server.stock;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.bike.server.auth.UserAuthRecord;
import com.bike.server.common.BusinessException;
import com.bike.server.lock.SkuLockService;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

class SkuInventoryAccessTest {
    final SkuInventoryMapper mapper=mock(SkuInventoryMapper.class);
    final SkuInventoryService inventory=new SkuInventoryService(mapper);
    @AfterEach void logout(){SecurityContextHolder.clearContext();}
    void login(String role,Long agent,String... permissions) {
        var authorities=new java.util.ArrayList<SimpleGrantedAuthority>();
        authorities.add(new SimpleGrantedAuthority("ROLE_"+role));
        for(String permission:permissions)authorities.add(new SimpleGrantedAuthority("PERM_"+permission));
        var auth=new UsernamePasswordAuthenticationToken("sku-reader",null,authorities);
        auth.setDetails(new UserAuthRecord(1,"sku-reader","",role,agent,null,true));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }
    @Test void agentQueryAlwaysUsesBoundAgentAndCapsPagination() {
        login("WEB_AGENT",9L,"AGENT_INVENTORY_READ");
        when(mapper.list(null,9L,999999900,100)).thenReturn(List.of());
        inventory.list(null,Long.MAX_VALUE,Long.MAX_VALUE);
        verify(mapper).list(null,9L,999999900,100);
        verify(mapper).count(null,9L);
    }
    @Test void missingAgentBindingAndPdaCannotReadEvenWithInventoryPermission() {
        login("WEB_AGENT",null,"AGENT_INVENTORY_READ");
        assertThatThrownBy(()->inventory.list(null,1,20)).isInstanceOf(BusinessException.class);
        login("PDA_OPERATOR",null,"INVENTORY_READ");
        assertThatThrownBy(()->inventory.list(null,1,20)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(mapper);
    }
    @Test void agentAndPdaCannotCreateInternalCustomerLocksEvenWithWritePermission() {
        var service=new SkuLockService(null,null,null,null,null,null,null,null,null);
        for(String role:List.of("WEB_AGENT","PDA_OPERATOR")) {
            login(role,9L,"AGENT_LOCK_WRITE");
            assertThatThrownBy(()->service.create(null)).isInstanceOf(BusinessException.class);
        }
    }
}
