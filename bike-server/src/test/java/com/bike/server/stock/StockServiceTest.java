package com.bike.server.stock;

import com.bike.server.order.OrderMapper;
import com.bike.server.order.SalesOrderView;
import com.bike.server.common.BusinessException;
import com.bike.server.vehicle.VehicleView;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;

class StockServiceTest {
    @org.junit.jupiter.api.BeforeEach
    void setAuthenticatedAdmin() {
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("test-admin", "N/A", List.of(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"))));
    }

    @org.junit.jupiter.api.AfterEach
    void clearAuthentication() {
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }

    @Test
    void stockOutRejectsDuplicateQrInOneBatch() {
        StockMapper stockMapper = Mockito.mock(StockMapper.class);
        OrderMapper orderMapper = Mockito.mock(OrderMapper.class);
        StockService service = com.bike.server.common.CustomerScopeTestSupport.isolated(new StockServiceImpl(stockMapper, orderMapper));

        assertThatThrownBy(() -> service.stockOut(new StockOutRequest(1L, 1L, List.of("QR-1", "QR-1"))))
                .isInstanceOf(com.bike.server.common.BusinessException.class)
                .hasMessageContaining("重复扫码");
        Mockito.verifyNoInteractions(orderMapper);
    }

    @Test
    void validateStockOutRequiresInStockVehicle() {
        StockMapper stockMapper = Mockito.mock(StockMapper.class);
        OrderMapper orderMapper = Mockito.mock(OrderMapper.class);
        VehicleView vehicle = new VehicleView();
        vehicle.setFrameSn("FRAME-1");
        vehicle.setStatus(1);
        Mockito.when(stockMapper.selectVehicleByQrForUpdate("QR-1")).thenReturn(vehicle);
        StockService service = com.bike.server.common.CustomerScopeTestSupport.isolated(new StockServiceImpl(stockMapper, orderMapper));

        StockValidationResult result = service.validateStockOut(new StockValidationRequest("QR-1"));

        org.assertj.core.api.Assertions.assertThat(result.valid()).isFalse();
        org.assertj.core.api.Assertions.assertThat(result.message()).contains("已入库");
    }

    @Test
    void validateStockOutRejectsVehicleFromDifferentOrderModel() {
        StockMapper stockMapper = Mockito.mock(StockMapper.class);
        OrderMapper orderMapper = Mockito.mock(OrderMapper.class);
        VehicleView vehicle = new VehicleView();
        vehicle.setFrameSn("FRAME-1");
        vehicle.setStatus(2);
        vehicle.setModelId(20L);
        SalesOrderView order = new SalesOrderView();
        order.setId(2L);
        order.setCustomerId(1L);
        order.setModelId(10L);
        order.setOrderQty(2);
        order.setShippedQty(0);
        order.setStatus("CONFIRMED");
        Mockito.when(stockMapper.selectVehicleByQrForUpdate("QR-1")).thenReturn(vehicle);
        Mockito.when(orderMapper.selectById(2L)).thenReturn(order);

        StockValidationResult result = com.bike.server.common.CustomerScopeTestSupport.isolated(new StockServiceImpl(stockMapper, orderMapper))
                .validateStockOut(new StockValidationRequest("QR-1", 1L, 2L));

        assertThat(result.valid()).isFalse();
        assertThat(result.message()).contains("车型不匹配");
    }

    @Test
    void validateStockOutRequiresCustomerAndOrderBeforeAcceptingInStockVehicle() {
        StockMapper stockMapper = Mockito.mock(StockMapper.class);
        OrderMapper orderMapper = Mockito.mock(OrderMapper.class);
        VehicleView vehicle = new VehicleView();
        vehicle.setFrameSn("FRAME-1");
        vehicle.setStatus(2);
        Mockito.when(stockMapper.selectVehicleByQrForUpdate("QR-1")).thenReturn(vehicle);

        StockValidationResult result = com.bike.server.common.CustomerScopeTestSupport.isolated(new StockServiceImpl(stockMapper, orderMapper))
                .validateStockOut(new StockValidationRequest("QR-1"));

        assertThat(result.valid()).isFalse();
        assertThat(result.message()).contains("必须先选择客户和订单");
        Mockito.verifyNoInteractions(orderMapper);
    }

    @Test
    void stockInRejectsVehicleAlreadyInStockWithSpecificCode() {
        StockMapper stockMapper = Mockito.mock(StockMapper.class);
        OrderMapper orderMapper = Mockito.mock(OrderMapper.class);
        Mockito.when(stockMapper.countEnabledLocation(1, 1)).thenReturn(1);
        VehicleView vehicle = new VehicleView();
        vehicle.setId(1L);
        vehicle.setFrameSn("FRAME-1");
        vehicle.setStatus(2);
        Mockito.when(stockMapper.selectVehicleByQrForUpdate("QR-1")).thenReturn(vehicle);

        BusinessException exception = org.junit.jupiter.api.Assertions.assertThrows(BusinessException.class,
                () -> com.bike.server.common.CustomerScopeTestSupport.isolated(new StockServiceImpl(stockMapper, orderMapper))
                        .stockIn(new StockInRequest("QR-1", 1L, 1L)));

        assertThat(exception.getCode()).isEqualTo("VEHICLE_ALREADY_STOCKED_IN");
    }

    @Test
    void stockOutRejectsVehicleAlreadyOutWithSpecificCode() {
        StockMapper stockMapper = Mockito.mock(StockMapper.class);
        OrderMapper orderMapper = Mockito.mock(OrderMapper.class);
        SalesOrderView order = new SalesOrderView();
        order.setId(2L);
        order.setCustomerId(1L);
        order.setOrderQty(2);
        order.setShippedQty(0);
        order.setStatus("CONFIRMED");
        Mockito.when(orderMapper.selectForUpdate(2L)).thenReturn(order);
        VehicleView vehicle = new VehicleView();
        vehicle.setId(1L);
        vehicle.setFrameSn("FRAME-1");
        vehicle.setStatus(3);
        Mockito.when(stockMapper.selectVehicleByQrForUpdate("QR-1")).thenReturn(vehicle);

        BusinessException exception = org.junit.jupiter.api.Assertions.assertThrows(BusinessException.class,
                () -> com.bike.server.common.CustomerScopeTestSupport.isolated(new StockServiceImpl(stockMapper, orderMapper))
                        .stockOut(new StockOutRequest(1L, 2L, List.of("QR-1"))));

        assertThat(exception.getCode()).isEqualTo("VEHICLE_ALREADY_STOCKED_OUT");
    }

    @Test
    void stockOutRejectsCustomerOrderMismatchBeforeChangingVehicle() {
        StockMapper stockMapper = Mockito.mock(StockMapper.class);
        OrderMapper orderMapper = Mockito.mock(OrderMapper.class);
        SalesOrderView order = new SalesOrderView();
        order.setId(2L);
        order.setCustomerId(9L);
        order.setOrderQty(2);
        order.setStatus("CONFIRMED");
        Mockito.when(orderMapper.selectForUpdate(2L)).thenReturn(order);

        BusinessException exception = org.junit.jupiter.api.Assertions.assertThrows(BusinessException.class,
                () -> com.bike.server.common.CustomerScopeTestSupport.isolated(new StockServiceImpl(stockMapper, orderMapper))
                        .stockOut(new StockOutRequest(1L, 2L, List.of("QR-1"))));

        assertThat(exception.getCode()).isEqualTo("ORDER_CUSTOMER_MISMATCH");
        Mockito.verify(stockMapper, Mockito.never()).updateVehicleStockOut(Mockito.anyLong(), Mockito.anyLong(), Mockito.anyLong());
    }

    @Test
    void stockOutRejectsVehicleWithDifferentOrderModelBeforeMutation() {
        StockMapper stockMapper = Mockito.mock(StockMapper.class);
        OrderMapper orderMapper = Mockito.mock(OrderMapper.class);
        SalesOrderView order = new SalesOrderView();
        order.setId(2L);
        order.setCustomerId(1L);
        order.setModelId(10L);
        order.setOrderQty(2);
        order.setShippedQty(0);
        order.setStatus("CONFIRMED");
        VehicleView vehicle = new VehicleView();
        vehicle.setId(1L);
        vehicle.setFrameSn("FRAME-1");
        vehicle.setModelId(20L);
        vehicle.setStatus(2);
        Mockito.when(orderMapper.selectForUpdate(2L)).thenReturn(order);
        Mockito.when(stockMapper.selectVehicleByQrForUpdate("QR-1")).thenReturn(vehicle);

        BusinessException exception = org.junit.jupiter.api.Assertions.assertThrows(BusinessException.class,
                () -> com.bike.server.common.CustomerScopeTestSupport.isolated(new StockServiceImpl(stockMapper, orderMapper))
                        .stockOut(new StockOutRequest(1L, 2L, List.of("QR-1"))));

        assertThat(exception.getCode()).isEqualTo("ORDER_MODEL_MISMATCH");
        Mockito.verify(stockMapper, Mockito.never()).updateVehicleStockOut(Mockito.anyLong(), Mockito.anyLong(), Mockito.anyLong());
    }

    @Test
    void stockOutRejectsBatchLargerThanOrderRemaining() {
        StockMapper stockMapper = Mockito.mock(StockMapper.class);
        OrderMapper orderMapper = Mockito.mock(OrderMapper.class);
        SalesOrderView order = new SalesOrderView();
        order.setId(2L);
        order.setCustomerId(1L);
        order.setOrderQty(1);
        order.setShippedQty(1);
        order.setStatus("PARTIAL_SHIPPED");
        Mockito.when(orderMapper.selectForUpdate(2L)).thenReturn(order);

        BusinessException exception = org.junit.jupiter.api.Assertions.assertThrows(BusinessException.class,
                () -> com.bike.server.common.CustomerScopeTestSupport.isolated(new StockServiceImpl(stockMapper, orderMapper))
                        .stockOut(new StockOutRequest(1L, 2L, List.of("QR-1"))));

        assertThat(exception.getCode()).isEqualTo("ORDER_QTY_NOT_ENOUGH");
        Mockito.verify(stockMapper, Mockito.never()).selectVehicleByQrForUpdate(Mockito.anyString());
    }

    @Test
    void stockInRejectsAssembledVehicleWithLocationAndStatusError() {
        StockMapper stockMapper = Mockito.mock(StockMapper.class);
        OrderMapper orderMapper = Mockito.mock(OrderMapper.class);
        Mockito.when(stockMapper.countEnabledLocation(1L, 1L)).thenReturn(1);
        VehicleView vehicle = new VehicleView();
        vehicle.setId(1L);
        vehicle.setFrameSn("FRAME-1");
        vehicle.setStatus(0);
        Mockito.when(stockMapper.selectVehicleByQrForUpdate("QR-1")).thenReturn(vehicle);

        BusinessException exception = org.junit.jupiter.api.Assertions.assertThrows(BusinessException.class,
                () -> com.bike.server.common.CustomerScopeTestSupport.isolated(new StockServiceImpl(stockMapper, orderMapper))
                        .stockIn(new StockInRequest("QR-1", 1L, 1L)));

        assertThat(exception.getCode()).isEqualTo("VEHICLE_NOT_ASSEMBLED");
        Mockito.verify(stockMapper, Mockito.never()).updateVehicleStockIn(Mockito.anyLong(), Mockito.anyLong(), Mockito.anyLong());
    }

    @Test
    void stockInRecordsWarehouseLocationOperatorAndStatusTransition() {
        StockMapper stockMapper = Mockito.mock(StockMapper.class);
        OrderMapper orderMapper = Mockito.mock(OrderMapper.class);
        Mockito.when(stockMapper.countEnabledLocation(10L, 20L)).thenReturn(1);
        VehicleView vehicle = new VehicleView();
        vehicle.setId(7L);
        vehicle.setFrameSn("FRAME-7");
        vehicle.setStatus(1);
        Mockito.when(stockMapper.selectVehicleByQrForUpdate("QR-7")).thenReturn(vehicle);
        Mockito.when(stockMapper.updateVehicleStockIn(7L, 10L, 20L)).thenReturn(1);
        StockRecordView record = new StockRecordView();
        record.setType("IN");
        record.setStatus("COMPLETED");
        Mockito.when(stockMapper.selectStockInByNo(Mockito.anyString())).thenReturn(record);

        StockRecordView result = com.bike.server.common.CustomerScopeTestSupport.isolated(new StockServiceImpl(stockMapper, orderMapper))
                .stockIn(new StockInRequest("QR-7", 10L, 20L));

        assertThat(result).isSameAs(record);
        Mockito.verify(stockMapper).updateVehicleStockIn(7L, 10L, 20L);
        Mockito.verify(stockMapper).insertStockIn(Mockito.anyString(), Mockito.eq(7L), Mockito.eq(10L), Mockito.eq(20L), Mockito.isNull());
        Mockito.verify(stockMapper).insertOperationLog(Mockito.eq("STOCK_IN"), Mockito.anyString(), Mockito.eq("整车入库"), Mockito.eq("FRAME-7"), Mockito.isNull(), Mockito.eq("test-admin"), Mockito.anyString(), Mockito.isNull());
    }

    @Test
    void stockOutCompletesVehicleAndOrderTogether() {
        StockMapper stockMapper = Mockito.mock(StockMapper.class);
        OrderMapper orderMapper = Mockito.mock(OrderMapper.class);
        SalesOrderView order = new SalesOrderView();
        order.setId(20L);
        order.setCustomerId(10L);
        order.setModelId(30L);
        order.setOrderQty(2);
        order.setShippedQty(0);
        order.setStatus("CONFIRMED");
        Mockito.when(orderMapper.selectForUpdate(20L)).thenReturn(order);
        VehicleView vehicle = new VehicleView();
        vehicle.setId(7L);
        vehicle.setFrameSn("FRAME-7");
        vehicle.setModelId(30L);
        vehicle.setStatus(2);
        Mockito.when(stockMapper.selectVehicleByQrForUpdate("QR-7")).thenReturn(vehicle);
        Mockito.when(stockMapper.updateVehicleStockOut(7L, 10L, 20L)).thenReturn(1);
        Mockito.when(orderMapper.increaseShipped(20L, 1)).thenReturn(1);
        Mockito.when(orderMapper.updateStatus(20L, "PARTIAL_SHIPPED")).thenReturn(1);
        StockRecordView record = new StockRecordView();
        record.setType("OUT");
        record.setStatus("COMPLETED");
        Mockito.when(stockMapper.selectStockOutByNo(Mockito.anyString())).thenReturn(record);

        StockRecordView result = com.bike.server.common.CustomerScopeTestSupport.isolated(new StockServiceImpl(stockMapper, orderMapper))
                .stockOut(new StockOutRequest(10L, 20L, List.of("QR-7")));

        assertThat(result).isSameAs(record);
        Mockito.verify(stockMapper).updateVehicleStockOut(7L, 10L, 20L);
        Mockito.verify(stockMapper).insertOrderVehicle(Mockito.eq(20L), Mockito.eq(7L), Mockito.anyString());
        Mockito.verify(orderMapper).increaseShipped(20L, 1);
        Mockito.verify(orderMapper).updateStatus(20L, "PARTIAL_SHIPPED");
    }

    @Test
    void stockOutStopsBatchWhenALaterVehicleIsInvalid() {
        StockMapper stockMapper = Mockito.mock(StockMapper.class);
        OrderMapper orderMapper = Mockito.mock(OrderMapper.class);
        SalesOrderView order = new SalesOrderView();
        order.setId(20L);
        order.setCustomerId(10L);
        order.setModelId(30L);
        order.setOrderQty(2);
        order.setShippedQty(0);
        order.setStatus("CONFIRMED");
        Mockito.when(orderMapper.selectForUpdate(20L)).thenReturn(order);
        VehicleView valid = new VehicleView();
        valid.setId(7L);
        valid.setFrameSn("FRAME-7");
        valid.setModelId(30L);
        valid.setStatus(2);
        VehicleView invalid = new VehicleView();
        invalid.setId(8L);
        invalid.setFrameSn("FRAME-8");
        invalid.setModelId(30L);
        invalid.setStatus(1);
        Mockito.when(stockMapper.selectVehicleByQrForUpdate("QR-7")).thenReturn(valid);
        Mockito.when(stockMapper.selectVehicleByQrForUpdate("QR-8")).thenReturn(invalid);
        Mockito.when(stockMapper.updateVehicleStockOut(7L, 10L, 20L)).thenReturn(1);

        assertThatThrownBy(() -> com.bike.server.common.CustomerScopeTestSupport.isolated(new StockServiceImpl(stockMapper, orderMapper))
                .stockOut(new StockOutRequest(10L, 20L, List.of("QR-7", "QR-8"))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("未入库");
        Mockito.verify(orderMapper, Mockito.never()).increaseShipped(Mockito.anyLong(), Mockito.anyInt());
        Mockito.verify(orderMapper, Mockito.never()).updateStatus(Mockito.anyLong(), Mockito.anyString());
    }

    @Test
    void cancelStockInWritesReasonAndOperatorAudit() {
        StockMapper stockMapper = Mockito.mock(StockMapper.class);
        OrderMapper orderMapper = Mockito.mock(OrderMapper.class);
        StockRecordView record = new StockRecordView();
        record.setId(4L);
        record.setVehicleId(10L);
        record.setFrameSn("FRAME-1");
        record.setStatus("COMPLETED");
        Mockito.when(stockMapper.selectStockIn(4L)).thenReturn(record);
        Mockito.when(stockMapper.updateVehicleStockInCancel(10L)).thenReturn(1);
        Mockito.when(stockMapper.cancelStockIn(4L, "盘点撤销", null)).thenReturn(1);
        Mockito.when(stockMapper.selectStockIn(4L)).thenReturn(record);

        StockRecordView result = com.bike.server.common.CustomerScopeTestSupport.isolated(new StockServiceImpl(stockMapper, orderMapper)).cancelStockIn(4L, "盘点撤销");

        assertThat(result).isSameAs(record);
        Mockito.verify(stockMapper).insertOperationLog("STOCK_IN_CANCEL", "4", "撤销入库", "FRAME-1", null, "test-admin", "盘点撤销", null);
    }

    @Test
    void cancelStockOutOnlyUpdatesVehicleForTheCurrentRecord() {
        StockMapper stockMapper = Mockito.mock(StockMapper.class);
        OrderMapper orderMapper = Mockito.mock(OrderMapper.class);
        StockRecordView record = new StockRecordView();
        record.setId(9L);
        record.setVehicleId(10L);
        record.setCustomerId(1L);
        record.setOrderId(2L);
        record.setOrderNo("SO-2");
        record.setFrameSn("FRAME-10");
        record.setStatus("COMPLETED");
        Mockito.when(stockMapper.selectStockOut(9L)).thenReturn(record);
        Mockito.when(stockMapper.updateVehicleStockOutCancel(10L, 1L, "SO-2", 9L)).thenReturn(1);
        Mockito.when(stockMapper.cancelStockOut(9L, "撤销出库", null)).thenReturn(1);
        Mockito.when(orderMapper.decreaseShipped(2L, 1)).thenReturn(1);
        Mockito.when(stockMapper.updateOrderStatusAfterCancel(2L, "CONFIRMED")).thenReturn(1);
        Mockito.when(stockMapper.selectStockOut(9L)).thenReturn(record);

        StockRecordView result = com.bike.server.common.CustomerScopeTestSupport.isolated(new StockServiceImpl(stockMapper, orderMapper)).cancelStockOut(9L, "撤销出库");

        assertThat(result).isSameAs(record);
        Mockito.verify(stockMapper).updateVehicleStockOutCancel(10L, 1L, "SO-2", 9L);
        Mockito.verify(stockMapper).cancelStockOut(9L, "撤销出库", null);
        Mockito.verify(orderMapper).decreaseShipped(2L, 1);
        Mockito.verify(stockMapper).updateOrderStatusAfterCancel(2L, "CONFIRMED");
    }

    @Test
    void cancelStockOutRejectsWhenOrderQuantityRollbackFails() {
        StockMapper stockMapper = Mockito.mock(StockMapper.class);
        OrderMapper orderMapper = Mockito.mock(OrderMapper.class);
        StockRecordView record = new StockRecordView();
        record.setId(9L);
        record.setVehicleId(10L);
        record.setCustomerId(1L);
        record.setOrderId(2L);
        record.setOrderNo("SO-2");
        record.setFrameSn("FRAME-10");
        record.setStatus("COMPLETED");
        Mockito.when(stockMapper.selectStockOut(9L)).thenReturn(record);
        Mockito.when(stockMapper.updateVehicleStockOutCancel(10L, 1L, "SO-2", 9L)).thenReturn(1);
        Mockito.when(stockMapper.cancelStockOut(9L, "撤销出库", null)).thenReturn(1);
        Mockito.when(orderMapper.decreaseShipped(2L, 1)).thenReturn(0);

        assertThatThrownBy(() -> com.bike.server.common.CustomerScopeTestSupport.isolated(new StockServiceImpl(stockMapper, orderMapper)).cancelStockOut(9L, "撤销出库"))
                .isInstanceOf(BusinessException.class)
                .extracting(BusinessException.class::cast)
                .extracting(BusinessException::getCode)
                .isEqualTo("ORDER_UPDATE_FAILED");
        Mockito.verify(orderMapper, Mockito.never()).updateStatus(Mockito.anyLong(), Mockito.anyString());
        Mockito.verify(stockMapper, Mockito.never()).updateOrderStatusAfterCancel(Mockito.anyLong(), Mockito.anyString());
    }
}
