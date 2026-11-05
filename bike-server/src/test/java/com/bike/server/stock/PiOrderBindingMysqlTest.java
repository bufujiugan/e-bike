package com.bike.server.stock;

import com.bike.server.common.BusinessException;
import com.bike.server.lock.LockOrderRequest;
import com.bike.server.lock.SkuLockRequest;
import com.bike.server.pi.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "BIKE_MYSQL_TEST_URL", matches = ".+")
class PiOrderBindingMysqlTest {
    @Test void explicitNewBusinessBuildsExactlyOneOrderAfterPayment() throws Exception {
        try (var f = new SkuCommitmentMysqlTest.Fixture(true)) {
            install(f);
            var json = new ObjectMapper().findAndRegisterModules();
            var original = request(null, 1);
            var document = json.valueToTree(original.document());
            ((com.fasterxml.jackson.databind.node.ObjectNode) document).set("business",
                    json.valueToTree(new PiBusinessTerms(1L, false, "GUANGTA", "NEW_BUSINESS")));
            var explicit = new PiCreateRequest(1L, null, null, "DOMESTIC", "CNY", null, null, null, null,
                    new BigDecimal("50"), new BigDecimal("100"), null,
                    json.treeToValue(document, PiDocumentSnapshot.class), original.items());
            var pi = f.tx(() -> f.pis.create(explicit));
            f.tx(() -> f.pis.confirm(pi.getId()));
            assertThat(count(f, "orderCount")).isZero();
            PaymentPriorityMysqlTest.install(f, null);
            var payment = new PiPaymentRequest("new-business-receipt", new BigDecimal("100"), "CNY", null, null, null);
            var paid = f.tx(() -> f.pis.recordPayment(pi.getId(), payment));
            f.tx(() -> f.pis.recordPayment(pi.getId(), payment));
            assertThat(paid.getFulfillment()).hasSize(1);
            assertThat(paid.getFulfillment().getFirst().reservationStatus()).isEqualTo("PAID_HELD");
            assertThat(count(f, "orderCount")).isEqualTo(1);
            assertThat(count(f, "linkCount")).isEqualTo(1);
            assertThat(count(f, "paymentCount")).isEqualTo(1);
        }
    }

    @Test void originalFiveUnitsAreLinkedConfirmedAndRetainedWithoutDuplicateOrderOrReceipt() throws Exception {
        try (var f = new SkuCommitmentMysqlTest.Fixture(true)) {
            install(f);
            for (int vehicle = 103; vehicle < 107; vehicle++) f.addVehicle(vehicle, 10);
            long orderId = order(f, 5);
            var option = f.tx(() -> f.session.getMapper(PiMapper.class).selectOrderOptions(null)).getFirst();
            assertThat(((Number) option.get("skuId")).longValue()).isEqualTo(10);
            assertThat(((Number) option.get("skuQuantity")).intValue()).isEqualTo(5);
            var pi = f.tx(() -> f.pis.create(request(orderId, 5)));
            PaymentPriorityMysqlTest.install(f, null);
            f.tx(() -> f.pis.confirm(pi.getId()));
            assertThat(f.orders.selectById(orderId).getStatus()).isEqualTo("CONFIRMED");
            var receipt = new PiPaymentRequest("original-receipt", new BigDecimal("500"), "CNY", null, null, null);
            var paid = f.tx(() -> f.pis.recordPayment(pi.getId(), receipt));
            f.tx(() -> f.pis.recordPayment(pi.getId(), receipt));
            assertThat(paid.getFulfillment()).hasSize(1);
            assertThat(paid.getFulfillment().getFirst().orderId()).isEqualTo(orderId);
            assertThat(paid.getFulfillment().getFirst().remainingQty()).isEqualTo(5);
            assertThat(paid.getFulfillment().getFirst().reservationStatus()).isEqualTo("PAID_HELD");
            assertThat(count(f, "orderCount")).isEqualTo(1);
            assertThat(count(f, "linkCount")).isEqualTo(1);
            assertThat(count(f, "reservedQuantity")).isEqualTo(5);
            assertThat(count(f, "paymentCount")).isEqualTo(1);
        }
    }

    @Test void warehouseShortageBlocksConfirmationUntilActualStockIsAvailable() throws Exception {
        try (var f = new SkuCommitmentMysqlTest.Fixture(true)) {
            install(f); long orderId = order(f, 5);
            var pi = f.tx(() -> f.pis.create(request(orderId, 5)));
            PaymentPriorityMysqlTest.install(f, null);
            code(() -> f.tx(() -> f.pis.confirm(pi.getId())), "PI_WAREHOUSE_STOCK_SHORTAGE");
            assertThat(f.tx(() -> f.pis.get(pi.getId())).getStatus()).isEqualTo("DRAFT");
            assertThat(f.orders.selectById(orderId).getStatus()).isEqualTo("DRAFT");
            for (int vehicle = 103; vehicle < 107; vehicle++) f.addVehicle(vehicle, 10);
            assertThat(f.tx(() -> f.pis.confirm(pi.getId())).getStatus()).isEqualTo("CONFIRMED");
            assertThat(count(f, "orderCount")).isEqualTo(1);
            assertThat(count(f, "reservedQuantity")).isEqualTo(5);
        }
    }

    @Test void oldUnlinkedDraftCanBindOnceAndCannotTakeAnotherPiOrder() throws Exception {
        try (var f = new SkuCommitmentMysqlTest.Fixture(true)) {
            install(f); long orderId = order(f, 1);
            var first = f.tx(() -> f.pis.create(request(null, 1)));
            code(() -> f.tx(() -> f.pis.confirm(first.getId())), "PI_ORDER_SOURCE_REQUIRED");
            var linked = f.tx(() -> f.pis.update(first.getId(), request(orderId, 1)));
            assertThat(linked.getSourceOrderId()).isEqualTo(orderId);
            assertThat(linked.getFulfillment()).hasSize(1);
            var second = f.tx(() -> f.pis.create(request(null, 1)));
            code(() -> f.tx(() -> f.pis.update(second.getId(), request(orderId, 1))), "ORDER_PI_EXISTS");
            assertThat(f.tx(() -> f.pis.get(second.getId())).getSourceOrderId()).isNull();
            assertThat(count(f, "linkCount")).isEqualTo(1);
        }
    }

    @Test void changesAfterDraftCreationAreCaughtBeforeConfirmationAndRolledBack() throws Exception {
        for (var scenario : Map.of("changeAgent", "PI_ORDER_AGENT_MISMATCH", "changeQuantity", "PI_ORDER_ITEMS_MISMATCH", "expireReservation", "ORDER_RESERVATION_EXPIRED").entrySet()) {
            try (var f = new SkuCommitmentMysqlTest.Fixture(true)) {
                install(f); long orderId = order(f, 1);
                var pi = f.tx(() -> f.pis.create(request(orderId, 1)));
                f.session.update("piOrderBindingFixture." + scenario.getKey(), Map.of("orderId", orderId));
                code(() -> f.tx(() -> f.pis.confirm(pi.getId())), scenario.getValue());
                assertThat(f.tx(() -> f.pis.get(pi.getId())).getStatus()).isEqualTo("DRAFT");
                assertThat(f.orders.selectById(orderId).getStatus()).isEqualTo("DRAFT");
                assertThat(count(f, "orderCount")).isEqualTo(1);
            }
        }
    }

    @Test void concurrentRequestsCannotBindOneOrderToTwoPis() throws Exception {
        try (var f = new SkuCommitmentMysqlTest.Fixture(true); var pool = Executors.newFixedThreadPool(2)) {
            install(f); long orderId = order(f, 1); var start = new CountDownLatch(1);
            Callable<String> create = () -> {
                start.await();
                try { f.tx(() -> f.pis.create(request(orderId, 1))); return "CREATED"; }
                catch (BusinessException error) { return error.getCode(); }
            };
            var first = pool.submit(create); var second = pool.submit(create); start.countDown();
            assertThat(List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("CREATED", "ORDER_PI_EXISTS");
            assertThat(count(f, "linkCount")).isEqualTo(1);
            assertThat(count(f, "reservedQuantity")).isEqualTo(1);
        }
    }

    private void install(SkuCommitmentMysqlTest.Fixture f) throws Exception {
        var config = f.session.getConfiguration();
        try (var input = Resources.getResourceAsStream("mapper/PiOrderBindingSqlFixture.xml")) {
            var xml = new String(input.readAllBytes(), StandardCharsets.UTF_8).replace("bike_", f.prefix);
            new XMLMapperBuilder(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)), config,
                    "PiOrderBindingSqlFixture", config.getSqlFragments()).parse();
        }
        ReflectionTestUtils.setField(f.pis, "orderBinding", new PiOrderBindingServiceImpl(f.session.getMapper(PiMapper.class),
                f.piSeries, f.orders, f.reservations, f.session.getMapper(PiBusinessMapper.class), new ObjectMapper().findAndRegisterModules()));
    }
    private long order(SkuCommitmentMysqlTest.Fixture f, int quantity) {
        var lock = f.tx(() -> f.locks.create(new SkuLockRequest("source-lock", 1L, null, null, null,
                List.of(new SkuLockRequest.Item(10, quantity))))).getFirst();
        return f.tx(() -> f.conversion.convert(lock.getId(), new LockOrderRequest(1L, quantity, "source-order", null))).getId();
    }
    private PiCreateRequest request(Long orderId, int quantity) {
        var document = new PiDocumentSnapshot("卖方", null, null, null, null, null, null, null, "收款后发货", "自提", "DOMESTIC", "1");
        return new PiCreateRequest(1L, null, orderId, "DOMESTIC", "CNY", null, null, null, null,
                new BigDecimal("50"), new BigDecimal("100"), null, document,
                List.of(new PiItemRequest(10L, quantity, new BigDecimal("100"), null, null)));
    }
    private long count(SkuCommitmentMysqlTest.Fixture f, String statement) {
        return f.session.selectOne("piOrderBindingFixture." + statement);
    }
    private void code(Runnable action, String expected) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.getCode()).isEqualTo(expected));
    }
}
