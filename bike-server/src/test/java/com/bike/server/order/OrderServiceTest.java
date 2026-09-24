package com.bike.server.order;

import com.bike.server.common.BusinessException;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderServiceTest {

    @org.junit.jupiter.api.BeforeEach
    void setAuthenticatedAdmin() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "test-admin", "N/A", java.util.List.of(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"))));
    }

    @org.junit.jupiter.api.AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void agentCannotInferPaidHoldFromOriginalDeadlineInEitherActiveMode() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "agent", "N/A", java.util.List.of(new SimpleGrantedAuthority("ROLE_WEB_AGENT"),
                new SimpleGrantedAuthority("PERM_ORDER_READ"))));
        for (String mode : java.util.List.of("TIMED", "PAID")) {
            OrderMapper mapper = Mockito.mock(OrderMapper.class);
            SalesOrderView order = order(7L, "SO-7", "CONFIRMED", 0, 2);
            order.setReservationHoldMode(mode);
            order.setReservationStatus("PAID".equals(mode) ? "PAID_HELD" : "TIMED_ACTIVE");
            order.setReservationExpiresAt(java.time.LocalDateTime.of(2020, 1, 1, 0, 0));
            order.setReservationHeldAt(java.time.LocalDateTime.of(2019, 12, 31, 0, 0));
            assertThat(order.getReservationExpiresAt()).isNotNull();
            Mockito.when(mapper.selectById(7L)).thenReturn(order);
            SalesOrderView response = com.bike.server.common.CustomerScopeTestSupport.isolated(new OrderServiceImpl(mapper)).get(7L);
            assertThat(response.getReservationStatus()).isEqualTo("ACTIVE");
            assertThat(response.getReservationExpiresAt()).isNull();
            assertThat(response.getReservationHoldMode()).isNull();
            assertThat(response.getReservationHeldAt()).isNull();
        }
    }

    @Test
    void cancelPersistsNormalizedReasonInAuditLog() {
        OrderMapper mapper = Mockito.mock(OrderMapper.class);
        SalesOrderView order = order(7L, "SO-7", "DRAFT", 0, 2);
        Mockito.when(mapper.selectById(7L)).thenReturn(order);
        Mockito.when(mapper.updateStatus(7L, "CANCELLED")).thenReturn(1);

        SalesOrderView result = com.bike.server.common.CustomerScopeTestSupport.isolated(new OrderServiceImpl(mapper)).cancel(7L, "  客户取消  ");

        assertThat(result).isSameAs(order);
        Mockito.verify(mapper).insertOperationLog("ORDER_UPDATE", "SO-7", "作废订单", null,
                "test-admin", "DRAFT", "CANCELLED", null, "客户取消");
    }

    @Test
    void cancelRejectsShippedOrderWithoutChangingStatus() {
        OrderMapper mapper = Mockito.mock(OrderMapper.class);
        Mockito.when(mapper.selectById(7L)).thenReturn(order(7L, "SO-7", "PARTIAL_SHIPPED", 1, 2));

        assertThatThrownBy(() -> com.bike.server.common.CustomerScopeTestSupport.isolated(new OrderServiceImpl(mapper)).cancel(7L, "作废"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不可直接作废");
        Mockito.verify(mapper, Mockito.never()).updateStatus(Mockito.anyLong(), Mockito.anyString());
    }

    @Test
    void updateRejectsQuantityBelowAlreadyShipped() {
        OrderMapper mapper = Mockito.mock(OrderMapper.class);
        Mockito.when(mapper.selectById(7L)).thenReturn(order(7L, "SO-7", "DRAFT", 2, 3));

        assertThatThrownBy(() -> com.bike.server.common.CustomerScopeTestSupport.isolated(new OrderServiceImpl(mapper))
                .update(7L, new SalesOrderCreateRequest(1L, null, 2L, 1, null, null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不能小于已出库数量");
        Mockito.verify(mapper, Mockito.never()).updateOrder(Mockito.anyLong(), Mockito.any());
    }

    private static SalesOrderView order(long id, String no, String status, int shipped, int quantity) {
        SalesOrderView order = new SalesOrderView();
        order.setId(id);
        order.setOrderNo(no);
        order.setStatus(status);
        order.setShippedQty(shipped);
        order.setOrderQty(quantity);
        order.setCustomerId(1L);
        return order;
    }
}
