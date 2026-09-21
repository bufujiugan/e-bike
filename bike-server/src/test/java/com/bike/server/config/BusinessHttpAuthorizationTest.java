package com.bike.server.config;

import com.bike.server.part.PartController;
import com.bike.server.part.PartService;
import com.bike.server.order.OrderController;
import com.bike.server.order.OrderService;
import com.bike.server.system.UserMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

@WebMvcTest({PartController.class, OrderController.class})
@Import(SecurityConfig.class)
class BusinessHttpAuthorizationTest {
    @Autowired MockMvc mvc;
    @MockitoBean PartService parts;
    @MockitoBean OrderService orders;
    @MockitoBean UserMapper users;
    @MockitoBean JwtTokenService tokens;
    @MockitoBean com.bike.server.auth.TokenRevocationMapper revocations;

    @Test void authorizedCustomRoleCanCreatePart() throws Exception {
        mvc.perform(post("/api/parts")
                .with(user("parts-clerk").authorities(new SimpleGrantedAuthority("PERM_PART_WRITE")))
                .contentType("application/json")
                .content("{\"partSn\":\"MOTOR10001\",\"partType\":\"MOTOR\"}"))
                .andExpect(status().isOk());
        verify(parts).create(any());
    }

    @Test void authorizedBusinessOperatorCanCreateOrder() throws Exception {
        mvc.perform(post("/api/orders")
                .with(user("sales").authorities(new SimpleGrantedAuthority("PERM_ORDER_WRITE")))
                .contentType("application/json")
                .content("{\"customerId\":1,\"modelId\":1,\"orderQty\":2}"))
                .andExpect(status().isOk());
        verify(orders).create(any());
    }

    @Test void readOnlyOperatorCannotCreatePart() throws Exception {
        mvc.perform(post("/api/parts")
                .with(user("reader").authorities(new SimpleGrantedAuthority("PERM_PART_READ")))
                .contentType("application/json")
                .content("{\"partSn\":\"MOTOR10001\",\"partType\":\"MOTOR\"}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(parts);
    }
}
