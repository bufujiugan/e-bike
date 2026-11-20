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

    @Test
    void readOnlyStaffCanSeeWhetherAnOrderHasPiWithoutContractWritePermission() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "reader", "N/A", java.util.List.of(new SimpleGrantedAuthority("PERM_ORDER_READ"))));
        var mapper = Mockito.mock(OrderMapper.class);
        var links = Mockito.mock(OrderContractMapper.class);
        Mockito.when(mapper.selectById(7L)).thenReturn(order(7L, "SO-7", "CONFIRMED", 0, 2));
        var service = com.bike.server.common.CustomerScopeTestSupport.isolated(new OrderServiceImpl(mapper));
        org.springframework.test.util.ReflectionTestUtils.setField(service, "contractLinks", links);
        Mockito.when(links.existsPiForOrder(7L)).thenReturn(true);
        assertThat(service.get(7L).getHasLinkedPi()).isTrue();
        Mockito.when(links.existsPiForOrder(7L)).thenReturn(false);
        assertThat(service.get(7L).getHasLinkedPi()).isFalse();
    }

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
            order.setPurchaseContractParty("内部合同方");
            order.setPurchaseContractNo("INTERNAL-BUY-7");
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
            assertThat(response.getPurchaseContractParty()).isNull();
            assertThat(response.getPurchaseContractNo()).isNull();
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
    void orderDetailIncludesSavedPurchaseContractForInternalUsers() {
        var mapper = Mockito.mock(OrderMapper.class);
        var contracts = Mockito.mock(com.bike.server.pi.PiPurchaseContractService.class);
        var service = com.bike.server.common.CustomerScopeTestSupport.isolated(new OrderServiceImpl(mapper));
        org.springframework.test.util.ReflectionTestUtils.setField(service, "purchaseContracts", contracts);
        Mockito.when(mapper.selectByNo("SO-7")).thenReturn(order(7L, "SO-7", "CONFIRMED", 0, 2));
        Mockito.when(contracts.findForOrder(7L)).thenReturn(new com.bike.server.pi.PiPurchaseContract("供应方", "BUY-7"));
        var response = service.getByNo("SO-7");
        assertThat(response.getPurchaseContractParty()).isEqualTo("供应方");
        assertThat(response.getPurchaseContractNo()).isEqualTo("BUY-7");
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

    @Test
    void confirmRequiresExplicitSalesTypeBeforeAnyStatusChange() {
        OrderMapper mapper = Mockito.mock(OrderMapper.class);
        SalesOrderView order = order(7L, "SO-7", "PENDING", 0, 2);
        Mockito.when(mapper.selectById(7L)).thenReturn(order);
        var service = com.bike.server.common.CustomerScopeTestSupport.isolated(new OrderServiceImpl(mapper));
        assertThatThrownBy(() -> service.confirm(7L)).isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getCode()).isEqualTo("ORDER_SALES_TYPE_REQUIRED");
        Mockito.verify(mapper, Mockito.never()).updateStatus(Mockito.anyLong(), Mockito.anyString());
        order.setSalesType(SalesType.DOMESTIC);
        Mockito.when(mapper.updateStatus(7L, "CONFIRMED")).thenReturn(1);
        service.confirm(7L);
        Mockito.verify(mapper).updateStatus(7L, "CONFIRMED");
    }

    @Test
    void salesTypeCanBeChosenOnlyOnceAndNeverOverwritesAnExistingType() {
        OrderMapper mapper = Mockito.mock(OrderMapper.class);
        SalesOrderView order = order(7L, "SO-7", "CONFIRMED", 0, 2);
        Mockito.when(mapper.selectById(7L)).thenReturn(order);
        Mockito.when(mapper.assignSalesType(7L, SalesType.EXPORT)).thenAnswer(call -> { order.setSalesType(SalesType.EXPORT); order.setSelectedSalesType(SalesType.EXPORT); return 1; });
        var service = com.bike.server.common.CustomerScopeTestSupport.isolated(new OrderServiceImpl(mapper));
        assertThatThrownBy(() -> service.assignSalesType(7L, "OTHER")).isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getCode()).isEqualTo("ORDER_SALES_TYPE_INVALID");
        assertThat(service.assignSalesType(7L, SalesType.EXPORT).getSalesType()).isEqualTo(SalesType.EXPORT);
        Mockito.verify(mapper).insertOperationLog("ORDER_UPDATE", "SO-7", "确定销售类型", null, "test-admin", "CONFIRMED", "CONFIRMED", null, "外销 · PI");
        // 同一类型重复提交幂等；改为其他类型被拒绝。
        assertThat(service.assignSalesType(7L, SalesType.EXPORT).getSalesType()).isEqualTo(SalesType.EXPORT);
        assertThatThrownBy(() -> service.assignSalesType(7L, SalesType.DOMESTIC)).isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getCode()).isEqualTo("ORDER_SALES_TYPE_LOCKED");
        Mockito.verify(mapper, Mockito.times(1)).assignSalesType(Mockito.anyLong(), Mockito.anyString());
        SalesOrderView cancelled = order(8L, "SO-8", "CANCELLED", 0, 2);
        Mockito.when(mapper.selectById(8L)).thenReturn(cancelled);
        assertThatThrownBy(() -> service.assignSalesType(8L, SalesType.DOMESTIC)).isInstanceOf(BusinessException.class);
    }

    @Test
    void listFilterAcceptsOnlyKnownSalesTypes() {
        OrderMapper mapper = Mockito.mock(OrderMapper.class);
        var service = com.bike.server.common.CustomerScopeTestSupport.isolated(new OrderServiceImpl(mapper));
        Mockito.when(mapper.selectPage(Mockito.isNull(), Mockito.isNull(), Mockito.isNull(), Mockito.eq(java.util.List.of("DOMESTIC", "PENDING")), Mockito.eq(0L), Mockito.eq(20L))).thenReturn(java.util.List.of());
        assertThat(service.list(null, null, " DOMESTIC,PENDING,DOMESTIC ", 1, 20).records()).isEmpty();
        assertThatThrownBy(() -> service.list(null, null, "DOMESTIC,UNKNOWN", 1, 20)).isInstanceOf(BusinessException.class);
        assertThat(SalesType.ofPi("EXPORT", null, true)).isEqualTo(SalesType.EXPORT);
        assertThat(SalesType.ofPi("DOMESTIC", null, true)).isEqualTo(SalesType.DOMESTIC);
        assertThat(SalesType.ofPi("DOMESTIC", null, false)).isEqualTo(SalesType.EUROPE);
        assertThat(SalesType.ofPi("EXPORT", "CHINA_SALES", false)).isEqualTo(SalesType.EXPORT);
        assertThat(SalesType.ofPi("DOMESTIC", "CZECH_PI", true)).isEqualTo(SalesType.EUROPE);
    }
}
