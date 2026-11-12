package com.bike.server.stock;

import com.bike.server.common.BusinessException;
import com.bike.server.lock.SkuLockRequest;
import com.bike.server.pi.PiBusinessService;
import com.bike.server.pi.PiShipmentGuard;
import com.bike.server.pi.PiShipmentMapper;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

@EnabledIfEnvironmentVariable(named = "BIKE_MYSQL_TEST_URL", matches = ".+")
class TimedReservationShipmentMysqlTest {
    @Test void confirmedTimedOrderWithoutPiCanValidateShipAndRestore() throws Exception {
        try (var fixture = new SkuCommitmentMysqlTest.Fixture(true)) {
            long orderId = confirmedOrder(fixture, 1);
            assertThat(fixture.orders.selectById(orderId).getReservationStatus()).isEqualTo("TIMED_ACTIVE");
            assertThat(fixture.reservations.selectFulfillmentSnapshot(4, orderId).getOwnedQty()).isZero();
            assertThat(fixture.tx(() -> fixture.stock.validateStockOut(
                    new StockValidationRequest("QR100", 1L, orderId))).valid()).isTrue();

            var receipt = fixture.tx(() -> fixture.stock.stockOut(
                    new StockOutRequest(1L, orderId, List.of("QR100"))));
            assertThat(fixture.orders.selectById(orderId).getStatus()).isEqualTo("SHIPPED");
            assertThat(fixture.orders.selectById(orderId).getShippedQty()).isEqualTo(1);
            assertThat(fixture.inventory.reservationItems(orderId).getFirst().shippedQty()).isEqualTo(1);
            assertThat(fixture.stockMapper.selectVehicleByQrForUpdate("QR100").getStatus()).isEqualTo(3);
            assertThatThrownBy(() -> fixture.tx(() -> fixture.stock.stockOut(
                    new StockOutRequest(1L, orderId, List.of("QR100"))))).isInstanceOf(BusinessException.class);
            fixture.tx(() -> fixture.stock.cancelStockOut(receipt.getId(), "核对撤销后占用恢复"));
            assertThat(fixture.inventory.reservationItems(orderId).getFirst().shippedQty()).isZero();
            assertThat(fixture.orders.selectById(orderId).getReservationStatus()).isEqualTo("TIMED_ACTIVE");
            assertThat(fixture.stockMapper.selectVehicleByQrForUpdate("QR100").getStatus()).isEqualTo(2);
        }
    }

    @Test void expiredOrReleasedReservationCannotShipDespiteFreeStock() throws Exception {
        for (String transition : List.of("expireOrder", "releaseOrder")) {
            try (var fixture = new SkuCommitmentMysqlTest.Fixture(true)) {
                long orderId = confirmedOrder(fixture, 1);
                fixture.session.update("paymentPriorityFixture." + transition, Map.of("orderId", orderId));
                assertThatThrownBy(() -> fixture.tx(() -> fixture.stock.validateStockOut(
                        new StockValidationRequest("QR100", 1L, orderId))))
                        .isInstanceOfSatisfying(BusinessException.class,
                                error -> assertThat(error.getCode()).isEqualTo("ORDER_RESERVATION_EXPIRED"));
                assertThatThrownBy(() -> fixture.tx(() -> fixture.stock.stockOut(
                        new StockOutRequest(1L, orderId, List.of("QR100")))))
                        .isInstanceOfSatisfying(BusinessException.class,
                                error -> assertThat(error.getCode()).isEqualTo("ORDER_RESERVATION_EXPIRED"));
                assertThat(fixture.orders.selectById(orderId).getShippedQty()).isZero();
            }
        }
    }

    @Test void timedOrderPreservesOtherCustomersSkuReservation() throws Exception {
        try (var fixture = new SkuCommitmentMysqlTest.Fixture(true)) {
            long orderId = confirmedOrder(fixture, 1);
            fixture.tx(() -> fixture.locks.create(new SkuLockRequest("other-customer", 2L,
                    null, null, null, List.of(new SkuLockRequest.Item(10, 1)))));
            assertThatThrownBy(() -> fixture.tx(() -> fixture.stock.stockOut(
                    new StockOutRequest(1L, orderId, List.of("QR100")))))
                    .isInstanceOfSatisfying(BusinessException.class,
                            error -> assertThat(error.getCode()).isEqualTo("SKU_INVENTORY_LOCK_CONFLICT"));
            assertThat(fixture.orders.selectById(orderId).getShippedQty()).isZero();
        }
    }

    @Test void timedOrderPreservesOtherModelCommitments() throws Exception {
        try (var fixture = new SkuCommitmentMysqlTest.Fixture(true)) {
            long orderId = confirmedOrder(fixture, 1);
            fixture.legacyLock(3);
            assertThatThrownBy(() -> fixture.tx(() -> fixture.stock.stockOut(
                    new StockOutRequest(1L, orderId, List.of("QR100")))))
                    .isInstanceOfSatisfying(BusinessException.class,
                            error -> assertThat(error.getCode()).isEqualTo("INVENTORY_LOCK_CONFLICT"));
        }
    }

    @Test void wrongSkuInBatchRollsBackPreviouslyProcessedVehicle() throws Exception {
        try (var fixture = new SkuCommitmentMysqlTest.Fixture(true)) {
            fixture.addVehicle(103, 10);
            long orderId = confirmedOrder(fixture, 2);
            assertThatThrownBy(() -> fixture.tx(() -> fixture.stock.stockOut(
                    new StockOutRequest(1L, orderId, List.of("QR100", "QR101")))))
                    .isInstanceOfSatisfying(BusinessException.class,
                            error -> assertThat(error.getCode()).isEqualTo("ORDER_SKU_MISMATCH"));
            assertThat(fixture.orders.selectById(orderId).getShippedQty()).isZero();
            assertThat(fixture.inventory.reservationItems(orderId).getFirst().shippedQty()).isZero();
            assertThat(fixture.stockMapper.selectVehicleByQrForUpdate("QR100").getStatus()).isEqualTo(2);
            assertThat(fixture.tx(() -> fixture.stockMapper.countStockRecords("OUT"))).isZero();
        }
    }

    @Test void concurrentDispatchOfSameTimedOrderCreatesOnlyOneShipment() throws Exception {
        try (var fixture = new SkuCommitmentMysqlTest.Fixture(true);
             var executor = Executors.newFixedThreadPool(2)) {
            long orderId = confirmedOrder(fixture, 1);
            var start = new CountDownLatch(1);
            java.util.concurrent.Callable<String> dispatch = () -> {
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("并发出库启动超时");
                try {
                    fixture.tx(() -> fixture.stock.stockOut(new StockOutRequest(1L, orderId, List.of("QR100"))));
                    return "SHIPPED";
                } catch (BusinessException error) {
                    return error.getCode();
                }
            };
            var first = executor.submit(dispatch);
            var second = executor.submit(dispatch);
            start.countDown();
            assertThat(List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("SHIPPED", "INVALID_STATUS_TRANSITION");
            assertThat(fixture.tx(() -> fixture.stockMapper.countStockRecords("OUT"))).isEqualTo(1);
            assertThat(fixture.inventory.reservationItems(orderId).getFirst().shippedQty()).isEqualTo(1);
        }
    }

    private long confirmedOrder(SkuCommitmentMysqlTest.Fixture fixture, int quantity) {
        var order = fixture.skuOrder(quantity);
        fixture.tx(() -> fixture.orders.updateStatus(order.getId(), "CONFIRMED"));
        var gate = new PiShipmentGuard(fixture.session.getMapper(PiShipmentMapper.class));
        ReflectionTestUtils.setField(gate, "series", fixture.piSeries);
        ReflectionTestUtils.setField(gate, "business", mock(PiBusinessService.class));
        ReflectionTestUtils.setField(fixture.stock, "piShipmentGuard", gate);
        return order.getId();
    }
}
