package com.bike.server.master;

import com.bike.server.auth.TokenRevocationMapper;
import com.bike.server.config.JwtTokenService;
import com.bike.server.config.SecurityConfig;
import com.bike.server.common.OperationLogMapper;
import com.bike.server.system.UserMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(CustomerProfileController.class)
@Import({SecurityConfig.class,CustomerProfileService.class})
class CustomerProfileHttpAuthorizationTest {
    @Autowired MockMvc mvc;
    @MockitoBean CustomerProfileMapper mapper;
    @MockitoBean OperationLogMapper logs;
    @MockitoBean UserMapper users;
    @MockitoBean JwtTokenService tokens;
    @MockitoBean TokenRevocationMapper revocations;
    private final String body="{\"revision\":0,\"address\":\"商务地址\",\"preferredCurrency\":\"EUR\",\"reason\":\"人工核对\"}";
    @Test void customPermissionCanUpdateProfileWithoutBeingAdministrator()throws Exception {
        when(mapper.lockCustomer(1)).thenReturn(1L);
        when(mapper.get(1)).thenReturn(new CustomerProfile(1,"C1","客户",null,null,"ENABLED",null,null,null,null,null,0,null),
                new CustomerProfile(1,"C1","客户",null,null,"ENABLED","商务地址",null,null,"EUR",null,1,null));
        when(mapper.insert(eq(1L),any(),any())).thenReturn(1);
        when(logs.insert(any(),any(),any(),any(),any(),any(),any(),any(),any())).thenReturn(1);
        mvc.perform(put("/api/master/customers/1/profile").with(user("sales").authorities(new SimpleGrantedAuthority("PERM_CUSTOMER_PROFILE_WRITE")))
                .contentType("application/json").content(body)).andExpect(status().isOk()).andExpect(jsonPath("$.data.revision").value(1));
        verify(mapper).insert(eq(1L),argThat(r->"EUR".equals(r.preferredCurrency())),any());
    }
    @Test void profileExceptionDoesNotOpenMasterWritesOrPermitExternalRoles()throws Exception {
        mvc.perform(put("/api/master/customers/1/profile").contentType("application/json").content(body)).andExpect(status().isUnauthorized());
        mvc.perform(put("/api/master/customers/1").with(user("writer").authorities(new SimpleGrantedAuthority("PERM_CUSTOMER_PROFILE_WRITE")))
                .contentType("application/json").content(body)).andExpect(status().isForbidden());
        mvc.perform(put("/api/master/customers/1/profile").with(user("reader").authorities(new SimpleGrantedAuthority("PERM_MASTER_READ")))
                .contentType("application/json").content(body)).andExpect(status().isForbidden());
        for(String role:new String[]{"WEB_AGENT","PDA_OPERATOR"}) {
            mvc.perform(put("/api/master/customers/1/profile").with(user("mixed").roles(role,"SUPER_ADMIN"))
                    .contentType("application/json").content(body)).andExpect(status().isForbidden());
        }
        verifyNoInteractions(mapper,logs);
    }
}
