package com.bike.server.order;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.bike.server.common.GlobalExceptionHandler;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class StockOutOrderOptionsTest {
    private final StockOutOrderOptionsMapper mapper = mock(StockOutOrderOptionsMapper.class);
    private final OrderService ordinaryOrders = mock(OrderService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new OrderController(ordinaryOrders),
            new StockOutOrderOptionsController(com.bike.server.common.CustomerScopeTestSupport.isolated(new StockOutOrderOptionsService(mapper))))
            .setControllerAdvice(new GlobalExceptionHandler()).build();

    @AfterEach void clearSecurity() { SecurityContextHolder.clearContext(); }

    private void as(String... authorities) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "ordinary-warehouse", "N/A", Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList()));
    }

    @Test void ordinaryPdaWarehouseCanSearchCustomerPageWithoutOrderReadPermission() throws Exception {
        as("ROLE_PDA_OPERATOR", "PERM_STOCK_OUT_OPERATE", "WORKSTATION_STOCK_OUT");
        when(mapper.selectPage(7L, "SO-", 40L, 20L)).thenReturn(List.of(
                new StockOutOrderOption(81, "SO-81", 7, "车型", 5, 2, "PARTIAL_SHIPPED")));
        when(mapper.count(7L, "SO-")).thenReturn(61L);
        mvc.perform(get("/api/orders/stock-out-options").param("customerId", "7")
                .param("keyword", "  SO-  ").param("page", "3").param("pageSize", "20"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.page").value(3))
                .andExpect(jsonPath("$.data.total").value(61))
                .andExpect(jsonPath("$.data.records[0].customerId").value(7))
                .andExpect(jsonPath("$.data.records[0].shippedQty").value(2))
                .andExpect(jsonPath("$.data.records[0].paidAmount").doesNotExist())
                .andExpect(jsonPath("$.data.records[0].reservationHoldMode").doesNotExist());
        verify(mapper).selectPage(7L, "SO-", 40L, 20L);
        verifyNoInteractions(ordinaryOrders);
    }

    @Test void ordinaryWebWarehouseUsesSameBoundStationAndPaginationLimits() throws Exception {
        as("PERM_STOCK_OUT_OPERATE", "WORKSTATION_STOCK_OUT");
        when(mapper.selectPage(7L, null, 0L, 100L)).thenReturn(List.of());
        mvc.perform(get("/api/orders/stock-out-options").param("customerId", "7")
                .param("page", "-2").param("pageSize", "10000"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.pageSize").value(100));
        verify(mapper).selectPage(7L, null, 0L, 100L);
    }

    @Test void agentCannotUseWarehouseOptionsEvenWithMisassignedPermissionAndStation() throws Exception {
        as("ROLE_WEB_AGENT", "PERM_STOCK_OUT_OPERATE", "WORKSTATION_STOCK_OUT");
        mvc.perform(get("/api/orders/stock-out-options").param("customerId", "7"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(mapper);
    }

    @Test void wrongStationAndOrderReaderCannotUseWarehouseOptions() throws Exception {
        as("ROLE_PDA_OPERATOR", "PERM_STOCK_OUT_OPERATE", "WORKSTATION_STOCK_IN");
        mvc.perform(get("/api/orders/stock-out-options").param("customerId", "7"))
                .andExpect(status().isForbidden());
        as("PERM_ORDER_READ", "WORKSTATION_STOCK_OUT");
        mvc.perform(get("/api/orders/stock-out-options").param("customerId", "7"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(mapper);
    }

    @Test void customerIsRequiredAndInvalidSearchCannotReachMapper() throws Exception {
        as("PERM_STOCK_OUT_OPERATE", "WORKSTATION_STOCK_OUT");
        mvc.perform(get("/api/orders/stock-out-options")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/orders/stock-out-options").param("customerId", "0"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/orders/stock-out-options").param("customerId", "7")
                .param("keyword", "X".repeat(101))).andExpect(status().isBadRequest());
        verifyNoInteractions(mapper);
    }
}
