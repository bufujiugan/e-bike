package com.bike.server.config;

import com.bike.server.part.PartController;
import com.bike.server.part.PartService;
import com.bike.server.order.OrderController;
import com.bike.server.order.OrderService;
import com.bike.server.system.UserController;
import com.bike.server.system.UserMapper;
import com.bike.server.system.UserPasswordAssignmentRequest;
import com.bike.server.system.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

@WebMvcTest({PartController.class, OrderController.class, UserController.class})
@Import(SecurityConfig.class)
class BusinessHttpAuthorizationTest {
    @Autowired MockMvc mvc;
    @MockitoBean PartService parts;
    @MockitoBean OrderService orders;
    @MockitoBean UserMapper users;
    @MockitoBean UserService userService;
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

    @Test void onlySuperAdminCanAssignPasswordAndInputMustBeValid() throws Exception {
        mvc.perform(put("/api/users/6/password").with(user("worker").roles("WEB_OPERATOR"))
                .contentType("application/json").content("{\"newPassword\":\"assigned-test-password\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/users/6/password").with(user("admin").roles("SUPER_ADMIN"))
                .contentType("application/json").content("{\"newPassword\":\"short\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(userService);

        mvc.perform(put("/api/users/6/password").with(user("admin").roles("SUPER_ADMIN"))
                .contentType("application/json").content("{\"newPassword\":\"assigned-test-password\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.requiresSignIn").value(false));
        verify(userService).assignPassword(6L, new UserPasswordAssignmentRequest("assigned-test-password"));
    }
}
