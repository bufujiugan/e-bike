package com.bike.server.stock;

import static org.assertj.core.api.Assertions.*;
import com.bike.server.common.BusinessException;
import com.bike.server.notification.DispatchNotification;
import com.bike.server.pi.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.security.core.context.SecurityContextHolder;

@EnabledIfEnvironmentVariable(named="BIKE_MYSQL_TEST_URL",matches=".+")
class PiDispatchMysqlTest {
    @Test void finalWrongVehicleRollsBackEarlierModelStockOrdersAuditAndBatch() throws Exception {
        try(var f=new SkuCommitmentMysqlTest.Fixture(true)) {
            var pi=ready(f);var request=request(pi,"QR101");
            long audits=f.scalar("SELECT COUNT(*) FROM @operation_log");
            code(()->f.tx(()->f.dispatch.dispatch(pi.getId(),request)),"ORDER_MODEL_MISMATCH");
            assertThat(f.scalar("SELECT COUNT(*) FROM @stock_out_batch")).isZero();
            assertThat(f.scalar("SELECT COUNT(*) FROM @stock_out_batch_item")).isZero();
            assertThat(f.scalar("SELECT COUNT(*) FROM @stock_out_record")).isZero();
            assertThat(f.scalar("SELECT COUNT(*) FROM @dispatch_notification")).isZero();
            assertThat(f.scalar("SELECT COUNT(*) FROM @operation_log")).isEqualTo(audits);
            assertThat(f.scalar("SELECT SUM(shipped_qty) FROM @sales_order_item")).isZero();
            assertThat(f.scalar("SELECT SUM(shipped_qty) FROM @order_reservation_item")).isZero();
            assertThat(f.scalar("SELECT status FROM @vehicle WHERE id=100")).isEqualTo(2);
            var batch=f.tx(()->f.dispatch.dispatch(pi.getId(),request(pi,"QR120")));
            assertThat(batch.originalQty()).isEqualTo(2);assertThat(batch.activeQty()).isEqualTo(2);
            assertThat(f.dispatchMapper.vehicles(batch.id())).hasSize(2);
        }
    }
    @Test void concurrentRetriesAndReorderedVehiclesReturnOneDurableBatch() throws Exception {
        try(var f=new SkuCommitmentMysqlTest.Fixture(true);var pool=Executors.newFixedThreadPool(2)) {
            var pi=ready(f);var request=request(pi,"QR120");var start=new CountDownLatch(1);
            var a=pool.submit(()->{start.await();return f.tx(()->f.dispatch.dispatch(pi.getId(),request));});
            var b=pool.submit(()->{start.await();return f.tx(()->f.dispatch.dispatch(pi.getId(),new PiDispatchRequest(request.requestId(),request.vehicles().reversed())));});
            start.countDown();assertThat(a.get(15,TimeUnit.SECONDS).id()).isEqualTo(b.get(15,TimeUnit.SECONDS).id());
            assertThat(f.scalar("SELECT COUNT(*) FROM @stock_out_batch")).isEqualTo(1);
            assertThat(f.scalar("SELECT COUNT(*) FROM @stock_out_record")).isEqualTo(2);
            assertThat(f.scalar("SELECT COUNT(*) FROM @dispatch_notification")).isEqualTo(1);
            assertThat(f.scalar("SELECT COUNT(*) FROM @operation_log WHERE action='PI整批出库'")).isEqualTo(1);
            code(()->f.tx(()->f.dispatch.dispatch(pi.getId(),new PiDispatchRequest(request.requestId(),List.of(request.vehicles().getFirst())))),"PI_DISPATCH_CONFLICT");
        }
    }
    @Test void cancellationChangesBatchProjectionAndRetryNeverReshipsCancelledVehicle() throws Exception {
        try(var f=new SkuCommitmentMysqlTest.Fixture(true)) {
            var pi=ready(f);var request=request(pi,"QR120");var batch=f.tx(()->f.dispatch.dispatch(pi.getId(),request));
            var vehicles=f.dispatchMapper.vehicles(batch.id());long id=((Number)vehicles.getFirst().get("stockOutId")).longValue();
            f.tx(()->f.stock.cancelStockOut(id,"撤销一台"));
            var retried=f.tx(()->f.dispatch.dispatch(pi.getId(),request));
            assertThat(retried.id()).isEqualTo(batch.id());assertThat(retried.status()).isEqualTo("PARTIALLY_CANCELLED");assertThat(retried.activeQty()).isEqualTo(1);
            assertThat(f.scalar("SELECT COUNT(*) FROM @stock_out_record")).isEqualTo(2);
            long second=((Number)vehicles.getLast().get("stockOutId")).longValue();f.tx(()->f.stock.cancelStockOut(second,"撤销另台"));
            assertThat(f.dispatchMapper.get(batch.id()).status()).isEqualTo("CANCELLED");
        }
    }
    @Test void warehouseProjectionOmitsCommercialAndPaymentFieldsAndOldEntryCreatesTrueBatch() throws Exception {
        try(var f=new SkuCommitmentMysqlTest.Fixture(true)) {
            var pi=ready(f);
            var warehouse=f.tx(()->f.dispatch.warehouse(pi.getId()));
            assertThat(warehouse.keySet()).containsExactlyInAnyOrder("id","piNo","customerId","customerName","status","orders");
            assertThat(warehouse.toString()).doesNotContain("paidAmount","unitPrice","PAID_HELD","TIMED_ACTIVE","deposit","expires");
            var first=pi.getFulfillment().getFirst();
            var record=f.tx(()->f.stock.stockOut(new StockOutRequest(1L,first.orderId(),List.of("QR100"))));
            var batch=f.dispatchMapper.batches(pi.getId()).getFirst();
            assertThat(batch.sourceType()).isEqualTo("ORDER_COMMAND");assertThat(batch.activeQty()).isEqualTo(1);
            assertThat(f.dispatchMapper.vehicles(batch.id()).getFirst().get("stockOutId")).isEqualTo(record.getId());
            var notice = f.tx(() -> f.dispatchNotifications.pending(0)).nextNotification();
            assertThat(notice.kind()).isEqualTo(DispatchNotification.Kind.ORDER);
            assertThat(notice.documentId()).isEqualTo(first.orderId());
            assertThat(notice.quantity()).isEqualTo(1);
        }
    }

    @Test void batchNoticeMatchesScopeAndReadStatusBelongsToTheAccount() throws Exception {
        try (var f = new SkuCommitmentMysqlTest.Fixture(true)) {
            f.exec("ALTER TABLE @sys_user ADD enabled TINYINT NOT NULL DEFAULT 1");
            f.exec("INSERT INTO @sys_user(id,display_name) VALUES (1,'管理员'),(2,'业务员'),(3,'其他业务员')");
            f.exec("CREATE TABLE @user_customer_scope(user_id BIGINT,customer_id BIGINT,PRIMARY KEY(user_id,customer_id))");
            f.tables.add("user_customer_scope");
            f.exec("INSERT INTO @user_customer_scope VALUES (2,1),(3,2)");

            var pi = ready(f);
            var batch = f.tx(() -> f.dispatch.dispatch(pi.getId(), request(pi, "QR120")));
            var notice = f.tx(() -> f.dispatchNotifications.pending(0)).nextNotification();
            assertThat(notice.kind()).isEqualTo(DispatchNotification.Kind.PI);
            assertThat(notice.documentNo()).isEqualTo(pi.getPiNo());
            assertThat(notice.batchNo()).isEqualTo(batch.batchNo());
            assertThat(notice.quantity()).isEqualTo(2);

            try {
                CustomerScopeMysqlTest.login(2, "WEB_OPERATOR", "PERM_ORDER_READ");
                assertThat(f.dispatchNotifications.list(false, 1).records()).isEmpty();
                CustomerScopeMysqlTest.login(3, "WEB_OPERATOR", "PERM_PI_READ");
                assertThat(f.dispatchNotifications.pending(0).unreadCount()).isZero();
                code(() -> f.transaction.execute(status -> {
                    f.dispatchNotifications.markRead(notice.id());
                    return null;
                }), "DISPATCH_NOTICE_NOT_FOUND");

                CustomerScopeMysqlTest.login(2, "WEB_OPERATOR", "PERM_PI_READ");
                assertThat(f.dispatchNotifications.pending(0).nextNotification()).isEqualTo(notice);
                f.transaction.executeWithoutResult(status -> f.dispatchNotifications.markRead(notice.id()));
                var readAt = f.dispatchNotifications.list(false, 1).records().getFirst().readAt();
                assertThat(readAt).isNotNull();
                f.transaction.executeWithoutResult(status -> f.dispatchNotifications.markRead(notice.id()));
                assertThat(f.dispatchNotifications.list(false, 1).records().getFirst().readAt()).isEqualTo(readAt);
                assertThat(f.dispatchNotifications.pending(0).nextNotification()).isNull();
                assertThat(f.dispatchNotifications.list(true, 1).records()).isEmpty();
            } finally {
                SecurityContextHolder.clearContext();
            }
            assertThat(f.tx(() -> f.dispatchNotifications.pending(0)).nextNotification()).isEqualTo(notice);
        }
    }
    static PiView ready(SkuCommitmentMysqlTest.Fixture f) {
        var body=PiConversionMysqlTest.request(f);var pi=f.tx(()->f.piConversion.convert(body));
        f.tx(()->f.pis.confirm(pi.getId()));
        f.tx(()->f.pis.recordPayment(pi.getId(),new PiPaymentRequest("paid",new BigDecimal("200"),"CNY",null,null,null)));
        return f.tx(()->f.pis.release(pi.getId(),new PiReleaseRequest("已核对")));
    }
    static PiDispatchRequest request(PiView pi,String finalQr) {
        return new PiDispatchRequest("dispatch-once",List.of(new PiDispatchRequest.Vehicle(pi.getFulfillment().getFirst().orderId(),"QR100"),new PiDispatchRequest.Vehicle(pi.getFulfillment().getLast().orderId(),finalQr)));
    }
    private static void code(Supplier<?> work,String expected){assertThatThrownBy(work::get).isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.getCode()).isEqualTo(expected));}
}
