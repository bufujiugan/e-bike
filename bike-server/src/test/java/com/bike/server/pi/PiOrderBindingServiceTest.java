package com.bike.server.pi;

import com.bike.server.common.BusinessException;
import com.bike.server.order.OrderMapper;
import com.bike.server.order.OrderReservationMapper;
import com.bike.server.order.SalesOrderView;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PiOrderBindingServiceTest {
    private final PiMapper pis = mock(PiMapper.class);
    private final PiSeriesMapper series = mock(PiSeriesMapper.class);
    private final OrderMapper orders = mock(OrderMapper.class);
    private final OrderReservationMapper reservations = mock(OrderReservationMapper.class);
    private final PiBusinessMapper warehouses = mock(PiBusinessMapper.class);
    private final PiOrderBindingService service = new PiOrderBindingServiceImpl(pis, series, orders, reservations, warehouses, new ObjectMapper());

    @Test void unlinkedDraftRequiresExplicitNewBusinessChoice() {
        var pi = pi();
        assertCode(() -> service.requireConfirmableBinding(pi), "PI_ORDER_SOURCE_REQUIRED");
        pi.setDocumentJson("{\"business\":{\"orderSource\":\"EXISTING_ORDER\"}}");
        assertCode(() -> service.requireConfirmableBinding(pi), "PI_ORDER_SOURCE_REQUIRED");
        pi.setDocumentJson("{\"business\":{\"orderSource\":\"NEW_BUSINESS\"}}");
        assertThatCode(() -> service.requireConfirmableBinding(pi)).doesNotThrowAnyException();
        assertCode(() -> service.requireConsistentFulfillment(pi), "PI_ORDER_LINK_REQUIRED");
    }

    @Test void sourceIdAloneCannotBypassMissingLinkEvenWithNewBusinessFlag() {
        var pi = pi(); pi.setSourceOrderId(7L);
        pi.setDocumentJson("{\"business\":{\"orderSource\":\"NEW_BUSINESS\"}}");
        assertCode(() -> service.requireConfirmableBinding(pi), "PI_ORDER_LINK_REQUIRED");
    }

    @Test void validatesBothCustomerAndAgentAgainstOriginalOrder() {
        var pi = linked(); var order = orders.selectForUpdate(7L);
        order.setCustomerId(3L);
        assertCode(() -> service.requireConfirmableBinding(pi), "PI_ORDER_CUSTOMER_MISMATCH");
        order.setCustomerId(2L); order.setAgentId(1L);
        assertCode(() -> service.requireConfirmableBinding(pi), "PI_ORDER_AGENT_MISMATCH");
        order.setAgentId(null);
        assertCode(() -> service.requireConfirmableBinding(pi), "PI_ORDER_AGENT_MISMATCH");
    }

    @Test void cannotSubstituteAnotherSkuOrQuantity() {
        var pi = linked();
        when(series.fulfillment(1L)).thenReturn(List.of(line(11L, 5, 0)));
        assertCode(() -> service.requireConfirmableBinding(pi), "PI_ORDER_ITEMS_MISMATCH");
        when(series.fulfillment(1L)).thenReturn(List.of(line(10L, 4, 0)));
        assertCode(() -> service.requireConfirmableBinding(pi), "PI_ORDER_ITEMS_MISMATCH");
        when(series.fulfillment(1L)).thenReturn(List.of(line(null, 5, 0)));
        assertCode(() -> service.requireConfirmableBinding(pi), "PI_ORDER_SKU_REQUIRED");
    }

    @Test void rejectsExpiredSourceBeforeFirstConfirmation() {
        var pi = linked(); when(reservations.expiredForOrder(7L)).thenReturn(1);
        assertCode(() -> service.requireConfirmableBinding(pi), "ORDER_RESERVATION_EXPIRED");
    }

    @Test void rejectsAlreadyShippedOrClosedSourceBeforeFirstConfirmation() {
        var pi = linked(); var order = orders.selectForUpdate(7L);
        order.setShippedQty(1);
        assertCode(() -> service.requireConfirmableBinding(pi), "PI_ORDER_STATUS_INVALID");
        order.setShippedQty(0); order.setStatus("CANCELLED");
        assertCode(() -> service.requireConfirmableBinding(pi), "PI_ORDER_STATUS_INVALID");
    }

    @Test void validExistingOrderReusesFiveUnitsWithoutAnyWrite() {
        var pi = linked();
        assertThatCode(() -> service.requireConfirmableBinding(pi)).doesNotThrowAnyException();
        verify(orders, never()).updateStatus(anyLong(), anyString());
        verify(series, never()).link(anyLong(), anyLong(), anyString());
    }

    @Test void revisionIncludesShippedQuantityAndAllowsClosedHistoricalLines() {
        var pi = linked(); pi.setPreviousRevisionId(99L);
        orders.selectForUpdate(7L).setStatus("PARTIAL_SHIPPED");
        when(series.fulfillment(1L)).thenReturn(List.of(line(10L, 3, 2), line(11L, 0, 0)));
        assertThatCode(() -> service.requireConfirmableBinding(pi)).doesNotThrowAnyException();
        verify(reservations, never()).expiredForOrder(anyLong());
    }

    @Test void missingLinkedOrderIsAnExplicitBusinessError() {
        var pi = linked(); when(orders.selectForUpdate(7L)).thenReturn(null);
        assertCode(() -> service.requireConfirmableBinding(pi), "ORDER_NOT_FOUND");
    }

    @Test void absentSourceMarkerKeepsHistoricalJsonAndConversionHashesStable() throws Exception {
        var json = new ObjectMapper();
        var legacy = "{\"warehouseId\":1,\"domesticShipment\":true,\"collectionEntity\":\"GUANGTA\"}";
        assertThat(json.writeValueAsString(new PiBusinessTerms(1L, true, "GUANGTA"))).isEqualTo(legacy);
        assertThat(json.readValue(legacy, PiBusinessTerms.class).orderSource()).isNull();
    }

    @Test void wrongWarehouseCannotConfirmFiveUnitsAgainstThreeAvailable() {
        var pi = linked(); pi.setSeriesId(1L);
        pi.setDocumentJson("{\"business\":{\"warehouseId\":2}}");
        when(warehouses.countWarehouseStock(2L, 10L)).thenReturn(3);
        assertCode(() -> service.requireConfirmableBinding(pi), "PI_WAREHOUSE_STOCK_SHORTAGE");
        when(warehouses.countWarehouseStock(2L, 10L)).thenReturn(6);
        when(warehouses.countWarehouseReserved(2L, 10L, 1L)).thenReturn(2);
        assertCode(() -> service.requireConfirmableBinding(pi), "PI_WAREHOUSE_STOCK_SHORTAGE");
        when(warehouses.countWarehouseReserved(2L, 10L, 1L)).thenReturn(1);
        assertThatCode(() -> service.requireConfirmableBinding(pi)).doesNotThrowAnyException();
    }

    private PiView linked() {
        var pi = pi(); pi.setSourceOrderId(7L);
        var order = new SalesOrderView(); order.setId(7L); order.setOrderNo("SO-ORIGINAL");
        order.setCustomerId(2L); order.setAgentId(2L); order.setStatus("CONFIRMED"); order.setOrderQty(5);
        when(series.orderIds(1L)).thenReturn(List.of(7L)); when(orders.selectForUpdate(7L)).thenReturn(order);
        var item = new PiItemView(); item.setSkuId(10L); item.setQuantity(5);
        when(pis.selectItems(1L)).thenReturn(List.of(item));
        when(series.fulfillment(1L)).thenReturn(List.of(line(10L, 5, 0)));
        return pi;
    }
    private PiView pi() {
        var pi = new PiView(); pi.setId(1L); pi.setCustomerId(2L); pi.setAgentId(2L); return pi;
    }
    private PiFulfillmentLine line(Long skuId, int remaining, int shipped) {
        return new PiFulfillmentLine(7L, "SO-ORIGINAL", "CONFIRMED", skuId, "SKU", remaining + shipped,
                shipped, 0, remaining, "TIMED_ACTIVE");
    }
    private void assertCode(Runnable action, String code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.getCode()).isEqualTo(code));
    }
}
