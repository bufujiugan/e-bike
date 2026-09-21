package com.bike.server.pi;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

import com.bike.server.common.BusinessException;
import com.bike.server.common.OperationLogMapper;
import com.bike.server.order.OrderInventoryGuard;
import com.bike.server.order.OrderReservationCoordinator;
import com.bike.server.order.OrderServiceImpl;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

/** Database time and real shared mutex determine payment retention; receivedAt never does. */
@EnabledIfEnvironmentVariable(named = "BIKE_MYSQL_TEST_URL", matches = ".+")
class PaidOrderReservationMysqlTest {
    private static final long DEADLINE = 1_900_000_010L;

    @Test
    void exactDepositBeforeDeadlineRetainsRemainingQuantityPastOriginalDeadlineAndRetryDoesNotRepeatHold() throws Exception {
        try (var f = new PiMysqlTest.Fixture()) {
            long id = reservedPi(f, "30.00", true);
            var originalDeadline = f.at(DEADLINE - 1, () -> f.reservations.selectForUpdate(1).expiresAt());
            PiView paid = f.at(DEADLINE - 1, () -> f.service.recordPayment(id, PiServiceTest.payment("deposit", "30.00")));
            assertThat(paid.getReservationStatus()).isEqualTo("PAID_HELD");
            f.at(DEADLINE + 100, () -> {
                assertThat(f.reservations.snapshot(4, 1).getOwnedQty()).isEqualTo(2);
                assertThat(f.reservations.expiredForOrder(1)).isZero();
                f.expiryJob.auditExpired();
                f.service.recordPayment(id, PiServiceTest.payment("deposit", "30.00"));
                var renewed = f.reservationService.renew(1, 48, "已保留不降级");
                assertThat(renewed.getReservationHoldMode()).isEqualTo("PAID");
                assertThat(renewed.getReservationExpiresAt()).isEqualTo(originalDeadline);
                return null;
            });
            assertThat(countAction(f, "收款达标保留订单占用")).isEqualTo(1);
            assertThat(countAction(f, "登记 PI 收款")).isEqualTo(1);
            assertThat(countAction(f, "订单库存占用到期")).isZero();
        }
    }

    @Test
    void OneCentBelowDepositExpiresAtExactDatabaseBoundary() throws Exception {
        try (var f = new PiMysqlTest.Fixture()) {
            long id = reservedPi(f, "30.00", true);
            assertThat(f.at(DEADLINE - 1, () -> f.service.recordPayment(id, PiServiceTest.payment("partial", "29.99")))
                    .getReservationStatus()).isEqualTo("TIMED_ACTIVE");
            f.at(DEADLINE, () -> {
                assertThat(f.reservations.snapshot(4, 1).getOwnedQty()).isZero();
                assertThat(f.reservations.expiredForOrder(1)).isEqualTo(1);
                f.expiryJob.auditExpired();
                f.expiryJob.auditExpired();
                assertThat(f.service.get(id).getReservationStatus()).isEqualTo("EXPIRED_REQUIRES_REACQUIRE");
                return null;
            });
            assertThat(countAction(f, "订单库存占用到期")).isEqualTo(1);
            assertThat(countAction(f, "收款达标保留订单占用")).isZero();
        }
    }

    @Test
    void LateAndBackdatedReceiptCommitsWithoutRevivingInventoryAndBlocksRelease() throws Exception {
        try (var f = new PiMysqlTest.Fixture()) {
            long id = reservedPi(f, "30.00", true);
            var request = new PiPaymentRequest("late", new BigDecimal("100.00"), "CNY",
                    null, LocalDateTime.of(2020, 1, 1, 0, 0), "晚录真实到账");
            PiView paid = f.at(DEADLINE, () -> f.service.recordPayment(id, request));
            assertThat(paid.getPaidAmount()).isEqualByComparingTo("100.00");
            assertThat(paid.getPaymentStatus()).isEqualTo("PAID");
            assertThat(paid.getReservationStatus()).isEqualTo("EXPIRED_REQUIRES_REACQUIRE");
            assertCode(() -> f.at(DEADLINE, () -> f.service.release(id, new PiReleaseRequest("到账已足额"))),
                    "ORDER_RESERVATION_EXPIRED");
            f.at(DEADLINE + 1, () -> f.service.recordPayment(id, request));
            assertThat(countAction(f, "收款已达条件但占用已到期")).isEqualTo(1);
            assertThat(f.at(DEADLINE + 1, () -> f.reservations.snapshot(4, 1).getOwnedQty())).isZero();
        }
    }

    @Test
    void ExpiredPaidReceiptNeedsExplicitReacquisitionAndInsufficientStockDoesNotErasePayment() throws Exception {
        try (var f = new PiMysqlTest.Fixture()) {
            long id = reservedPi(f, "30.00", true);
            f.at(DEADLINE, () -> f.service.recordPayment(id, PiServiceTest.payment("late-deposit", "30.00")));
            f.execute("UPDATE " + f.prefix + "vehicle SET status=3 WHERE id=101");
            assertCode(() -> f.at(DEADLINE + 1, () -> f.reservationService.renew(1, 48, "核对重占")), "INVENTORY_NOT_ENOUGH");
            PiView stillPaid = f.at(DEADLINE + 1, () -> f.service.get(id));
            assertThat(stillPaid.getPaidAmount()).isEqualByComparingTo("30.00");
            assertThat(stillPaid.getReservationStatus()).isEqualTo("EXPIRED_REQUIRES_REACQUIRE");
            f.execute("UPDATE " + f.prefix + "vehicle SET status=2 WHERE id=101");
            var reacquired = f.at(DEADLINE + 2, () -> f.reservationService.renew(1, 48, "库存已足够，显式重新占用"));
            assertThat(reacquired.getReservationStatus()).isEqualTo("PAID_HELD");
            assertThat(f.at(DEADLINE + 500_000, () -> f.reservations.snapshot(4, 1).getOwnedQty())).isEqualTo(2);
        }
    }

    @Test
    void ZeroDepositConfirmRetainsOnlyBeforeDeadlineAndCancellationReleasesIt() throws Exception {
        for (boolean expired : List.of(false, true)) {
            try (var f = new PiMysqlTest.Fixture()) {
                long id = reservedPi(f, "0.00", false);
                long now = expired ? DEADLINE : DEADLINE - 1;
                PiView confirmed = f.at(now, () -> f.service.confirm(id));
                assertThat(confirmed.getReservationStatus()).isEqualTo(expired ? "EXPIRED_REQUIRES_REACQUIRE" : "PAID_HELD");
                f.at(now, () -> f.service.cancel(id, new PiCancelRequest("未发生收款或出库，取消履约")));
                assertThat(f.at(now, () -> f.reservations.selectForUpdate(1).holdMode())).isEqualTo("RELEASED");
                assertCode(() -> f.at(now, () -> f.reservationService.renew(1, 24, "不能重开已取消PI")), "ORDER_RESERVATION_RELEASED");
            }
        }
    }

    @Test
    void PartialAndCompleteShipmentsPreservePaidModeForControlledRestorationThenClosingReleasesRemainder() throws Exception {
        try (var f = new PiMysqlTest.Fixture()) {
            long id = reservedPi(f, "30.00", true);
            f.at(DEADLINE - 1, () -> f.service.recordPayment(id, PiServiceTest.payment("deposit", "30.00")));
            f.at(DEADLINE + 1, () -> {
                var inventory = new OrderInventoryGuard(f.reservations);
                assertThat(f.orders.increaseShipped(1, 1)).isEqualTo(1);
                f.orders.updateStatus(1, "PARTIAL_SHIPPED");
                new JdbcTemplate(f.source).update("UPDATE " + f.prefix + "vehicle SET status=3 WHERE id=100");
                assertThat(f.reservations.snapshot(4, 1).getOwnedQty()).isEqualTo(1);
                assertThat(f.reservations.snapshot(4, 1).availableForOrder()).isEqualTo(1);
                inventory.requireShipment(f.orders.selectById(1L), 1);
                f.orders.increaseShipped(1, 1);
                f.orders.updateStatus(1, "SHIPPED");
                new JdbcTemplate(f.source).update("UPDATE " + f.prefix + "vehicle SET status=3 WHERE id=101");
                assertThat(f.reservations.snapshot(4, 1).getOwnedQty()).isZero();
                assertThat(f.reservations.selectForUpdate(1).holdMode()).isEqualTo("PAID");
                inventory.requireRestore(1);
                f.orders.decreaseShipped(1, 1);
                f.orders.updateStatus(1, "PARTIAL_SHIPPED");
                new JdbcTemplate(f.source).update("UPDATE " + f.prefix + "vehicle SET status=2 WHERE id=101");
                assertThat(f.reservations.snapshot(4, 1).getOwnedQty()).isEqualTo(1);
                assertThat(f.reservations.snapshot(4, 1).availableForOrder()).isEqualTo(1);
                f.reservationService.closeRemainder(1, "客户不再接收剩余车辆");
                assertThat(f.reservations.snapshot(4, 1).getOwnedQty()).isZero();
                assertThat(f.reservations.selectForUpdate(1).holdMode()).isEqualTo("RELEASED");
                assertCode(() -> { inventory.requireRestore(1); return null; }, "ORDER_RESERVATION_EXPIRED");
                return null;
            });
        }
    }

    @Test
    void CancellingUnshippedOrderReleasesPaidHoldWithoutChangingFinancialFacts() throws Exception {
        try (var f = new PiMysqlTest.Fixture()) {
            long id = reservedPi(f, "30.00", true);
            f.at(DEADLINE - 1, () -> f.service.recordPayment(id, PiServiceTest.payment("deposit", "30.00")));
            var service = new OrderServiceImpl(f.orders);
            ReflectionTestUtils.setField(service, "guard", f.guard);
            ReflectionTestUtils.setField(service, "reservations", f.coordinator);
            f.at(DEADLINE + 1, () -> service.cancel(1, "业务取消，款项另行处理"));
            assertThat(f.at(DEADLINE + 1, () -> f.reservations.selectForUpdate(1).holdMode())).isEqualTo("RELEASED");
            assertThat(f.at(DEADLINE + 1, () -> f.service.get(id).getPaidAmount())).isEqualByComparingTo("30.00");
        }
    }

    @Test
    void FullyShippedTimedOrderCanRenewForControlledRestorationWithoutClaimingUnshippedQuantity() throws Exception {
        try (var f = new PiMysqlTest.Fixture()) {
            reservedPi(f, "30.00", true);
            f.execute("UPDATE " + f.prefix + "sales_order_item SET shipped_qty=2 WHERE order_id=1");
            f.execute("UPDATE " + f.prefix + "sales_order SET status='SHIPPED' WHERE id=1");
            f.execute("UPDATE " + f.prefix + "vehicle SET status=3");
            f.at(DEADLINE + 1, () -> {
                var inventory = new OrderInventoryGuard(f.reservations);
                assertCode(() -> { inventory.requireRestore(1); return null; }, "ORDER_RESERVATION_EXPIRED");
                var renewed = f.reservationService.renew(1, 24, "核对后准备撤销出库");
                assertThat(renewed.getReservationHoldMode()).isEqualTo("TIMED");
                assertThat(renewed.getReservationStatus()).isEqualTo("FULFILLED");
                assertThat(f.reservations.snapshot(4, 1).getOwnedQty()).isZero();
                inventory.requireRestore(1);
                return null;
            });
        }
    }

    @Test
    void RetentionAuditFailureRollsBackReceiptAndHoldTogether() throws Exception {
        try (var f = new PiMysqlTest.Fixture()) {
            long id = reservedPi(f, "30.00", true);
            var coordinator = new OrderReservationCoordinator(f.guard, f.reservations, mock(OperationLogMapper.class), f.json);
            var service = new PiServiceImpl(f.mapper, mock(PiDocumentService.class), f.logs, f.json, f.validation.getValidator(), coordinator);
            assertCode(() -> f.at(DEADLINE - 1, () -> service.recordPayment(id, PiServiceTest.payment("audit-fail", "30.00"))), "ORDER_AUDIT_FAILED");
            PiView unchanged = f.at(DEADLINE - 1, () -> f.service.get(id));
            assertThat(unchanged.getPayments()).isEmpty();
            assertThat(unchanged.getReservationStatus()).isEqualTo("TIMED_ACTIVE");
            assertThat(unchanged.getPaidAmount()).isEqualByComparingTo("0.00");
            assertThat(countAction(f, "收款达标保留订单占用")).isZero();
        }
    }

    @Test
    void PaymentAndExpirySerializeThroughRealMutexInBothOrders() throws Exception {
        for (boolean paymentFirst : List.of(true, false)) {
            try (var f = new PiMysqlTest.Fixture(); var pool = Executors.newFixedThreadPool(2)) {
                long id = reservedPi(f, "30.00", true);
                var firstChanged = new CountDownLatch(1);
                var commitFirst = new CountDownLatch(1);
                var secondStarted = new CountDownLatch(1);
                Future<?> first = pool.submit(() -> f.at(paymentFirst ? DEADLINE - 1 : DEADLINE, () -> {
                    if (paymentFirst) f.service.recordPayment(id, PiServiceTest.payment("race", "30.00"));
                    else f.expiryJob.auditExpired();
                    firstChanged.countDown();
                    await(commitFirst);
                    return null;
                }));
                try {
                    await(firstChanged);
                    Future<?> second = pool.submit(() -> f.at(DEADLINE, () -> {
                        secondStarted.countDown();
                        if (paymentFirst) f.expiryJob.auditExpired();
                        else f.service.recordPayment(id, PiServiceTest.payment("race", "30.00"));
                        return null;
                    }));
                    await(secondStarted);
                    assertThatThrownBy(() -> second.get(150, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                    commitFirst.countDown();
                    first.get(10, TimeUnit.SECONDS);
                    second.get(10, TimeUnit.SECONDS);
                } finally { commitFirst.countDown(); }
                PiView result = f.at(DEADLINE + 1, () -> f.service.get(id));
                assertThat(result.getPaidAmount()).isEqualByComparingTo("30.00");
                assertThat(result.getReservationStatus()).isEqualTo(paymentFirst ? "PAID_HELD" : "EXPIRED_REQUIRES_REACQUIRE");
                assertThat(countAction(f, "收款达标保留订单占用")).isEqualTo(paymentFirst ? 1 : 0);
                assertThat(countAction(f, "订单库存占用到期")).isEqualTo(paymentFirst ? 0 : 1);
            }
        }
    }

    @Test
    void AgentReadsOnlyGenericActiveStateAndCannotReacquire() throws Exception {
        try (var f = new PiMysqlTest.Fixture()) {
            f.execute("UPDATE " + f.prefix + "sales_order SET agent_id=9 WHERE id=1");
            reserve(f);
            var request = f.request(9L, 1L);
            long id = f.at(DEADLINE - 2, () -> f.service.create(request)).getId();
            f.at(DEADLINE - 2, () -> f.service.confirm(id));
            f.at(DEADLINE - 1, () -> f.service.recordPayment(id, PiServiceTest.payment("agent-hidden", "30.00")));
            f.agent(9L, () -> {
                PiView view = f.service.get(id);
                assertThat(view.getReservationStatus()).isEqualTo("ACTIVE");
                assertThat(view.getPaidAmount()).isNull();
                assertThat(view.getPayments()).isEmpty();
                assertCode(() -> f.reservationService.renew(1, 48, "代理商不可重占"), "PERMISSION_DENIED");
                var order = f.orders.selectById(1L);
                assertThat(order.getReservationExpiresAt()).isNotNull();
                OrderServiceImpl.redactReservationForAgent(order);
                assertThat(order.getReservationStatus()).isEqualTo("ACTIVE");
                assertThat(order.getReservationHoldMode()).isNull();
                assertThat(order.getReservationHeldAt()).isNull();
                assertThat(order.getReservationExpiresAt()).isNull();
                return null;
            });
        }
    }

    private static long reservedPi(PiMysqlTest.Fixture f, String deposit, boolean confirm) throws Exception {
        reserve(f);
        PiCreateRequest r = f.request(null, 1L);
        var request = new PiCreateRequest(r.customerId(), r.agentId(), r.sourceOrderId(), r.tradeType(), r.currencyCode(),
                r.plannedShipDate(), r.discountAmount(), r.taxAmount(), r.freightAmount(), new BigDecimal(deposit),
                r.shipmentRequiredAmount(), r.remark(), r.document(), r.items());
        long id = f.at(DEADLINE - 2, () -> f.service.create(request)).getId();
        if (confirm) f.at(DEADLINE - 2, () -> f.service.confirm(id));
        return id;
    }

    private static void reserve(PiMysqlTest.Fixture f) throws Exception {
        f.execute("INSERT INTO " + f.prefix + "inventory_lock VALUES(1,'LOCK-1',4,2,0,2,'CONVERTED',FROM_UNIXTIME(" + DEADLINE + "))");
        f.execute("INSERT INTO " + f.prefix + "order_reservation(order_id,source_lock_id,request_id,model_id,reserved_qty,expires_at) VALUES(1,1,'source-order',4,2,FROM_UNIXTIME(" + DEADLINE + "))");
    }

    private static long countAction(PiMysqlTest.Fixture f, String action) throws Exception {
        return f.scalar("SELECT COUNT(*) FROM " + f.prefix + "operation_log WHERE action='" + action + "'");
    }

    private static void assertCode(java.util.function.Supplier<?> action, String code) {
        assertThatThrownBy(action::get).isInstanceOfSatisfying(BusinessException.class,
                error -> assertThat(error.getCode()).isEqualTo(code));
    }

    private static void await(CountDownLatch latch) {
        try { assertThat(latch.await(10, TimeUnit.SECONDS)).as("transaction checkpoint").isTrue(); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException(error); }
    }
}
