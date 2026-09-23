package com.bike.server.stock;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.bike.server.common.*;
import com.bike.server.order.OrderReservationCoordinator;
import com.bike.server.pi.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.util.ReflectionTestUtils;

@EnabledIfEnvironmentVariable(named="BIKE_MYSQL_TEST_URL", matches=".+")
class PiRevisionLifecycleMysqlTest {
    static PiRevisionServiceImpl revisions(SkuCommitmentMysqlTest.Fixture f) {
        return new PiRevisionServiceImpl((PiMapper) ReflectionTestUtils.getField(f.pis,"mapper"), f.piSeries,
                (OperationLogMapper) ReflectionTestUtils.getField(f.pis,"logs"), new ObjectMapper().findAndRegisterModules(), f.pis,
                (OrderReservationCoordinator) ReflectionTestUtils.getField(f.pis,"reservations"));
    }
    static PiRevisionRequest request(String key) { return new PiRevisionRequest(key,1,"客户核对条款"); }
    static PiCreateRequest edit(PiView pi, String currency, long customer) {
        return new PiCreateRequest(customer,pi.getAgentId(),pi.getSourceOrderId(),pi.getTradeType(),currency,pi.getPlannedShipDate(),
                BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO,pi.getDepositRequiredAmount(),pi.getShipmentRequiredAmount(),
                "修订商业资料",pi.getDocument(), pi.getItems().stream().map(i -> new PiItemRequest(i.getSkuId(),i.getQuantity(),
                        new BigDecimal("120.00"),BigDecimal.ZERO,BigDecimal.ZERO)).toList());
    }
    @Test void paidAndShippedPiCanPublishCommercialRevisionWithoutChangingExecutionOrMixingCurrencies() throws Exception {
        try(var f=new SkuCommitmentMysqlTest.Fixture(true)) {
            var original=PiPaymentCorrectionMysqlTest.paid(f);var svc=revisions(f);
            long order=original.getFulfillment().stream().filter(x->x.skuId()==10).findFirst().orElseThrow().orderId();
            f.tx(()->f.stock.stockOut(new StockOutRequest(1L,order,List.of("QR100"))));
            var draft=f.tx(()->svc.create(original.getId(),request("commercial")));
            assertThatThrownBy(()->f.tx(()->f.pis.update(draft.getId(),edit(draft,"USD",1)))).hasMessageContaining("币种一致");
            assertThatThrownBy(()->f.tx(()->f.pis.update(draft.getId(),edit(draft,"CNY",2)))).hasMessageContaining("保留原客户");
            var changed=f.tx(()->f.pis.update(draft.getId(),edit(draft,"CNY",1)));
            assertThat(changed.getTotalAmount()).isEqualByComparingTo("240.00");
            var published=f.tx(()->f.pis.confirm(draft.getId()));
            assertThat(published.getStatus()).isEqualTo("CONFIRMED");assertThat(published.getReleaseStatus()).isEqualTo("BLOCKED");
            assertThat(published.getPaidAmount()).isEqualByComparingTo("200");
            assertThat(f.tx(()->f.pis.get(original.getId())).getStatus()).isEqualTo("SUPERSEDED");
            assertThat(f.tx(()->f.pis.get(original.getId())).getTotalAmount()).isEqualByComparingTo("200");
            assertThat(f.scalar("SELECT COUNT(*) FROM @stock_out_record WHERE status='COMPLETED'")).isEqualTo(1);
            assertThat(f.orders.selectById(order).getStatus()).isEqualTo("SHIPPED");
            assertThat(f.tx(()->svc.create(original.getId(),request("commercial"))).getId()).isEqualTo(draft.getId());
            assertThatThrownBy(()->f.tx(()->f.pis.cancel(draft.getId(),new PiCancelRequest("不能抹旧收款")))).hasMessageContaining("收款记录");
            f.tx(()->f.pis.release(draft.getId(),new PiReleaseRequest("重新核对修订")));
        }
    }
    @Test void cancellingRevisionDoesNotReleaseCurrentOrdersAndCancelledNumbersAreNeverReused() throws Exception {
        try(var f=new SkuCommitmentMysqlTest.Fixture(true)) {
            var original=PiPaymentCorrectionMysqlTest.paid(f);var svc=revisions(f);
            var first=f.tx(()->svc.create(original.getId(),request("cancelled-draft")));
            assertThatThrownBy(()->f.tx(()->svc.create(original.getId(),request("second-draft")))).hasMessageContaining("已有修订草稿");
            f.tx(()->f.pis.cancel(first.getId(),new PiCancelRequest("取消本次修订")));
            var current=f.tx(()->f.pis.get(original.getId()));
            assertThat(current.getStatus()).isEqualTo("RELEASED");
            assertThat(current.getFulfillment()).allSatisfy(line->{assertThat(line.remainingQty()).isEqualTo(1);assertThat(line.reservationStatus()).isEqualTo("PAID_HELD");});
            var next=f.tx(()->svc.create(original.getId(),request("replacement-draft")));
            assertThat(next.getRevision()).isEqualTo(3);
            assertThat(f.tx(()->svc.create(original.getId(),request("cancelled-draft"))).getStatus()).isEqualTo("CANCELLED");
            assertThat(f.tx(()->svc.history(original.getId()))).extracting(PiView::getRevision).containsExactly(1,2,3);
        }
    }
    @Test void concurrentRetriesCreateOnceAndFailedAuditRollsBackVersionAllocationAndClones() throws Exception {
        try(var f=new SkuCommitmentMysqlTest.Fixture(true);var pool=Executors.newFixedThreadPool(2)) {
            var original=PiPaymentCorrectionMysqlTest.paid(f);var svc=revisions(f);
            var logs=ReflectionTestUtils.getField(svc,"logs");ReflectionTestUtils.setField(svc,"logs",mock(OperationLogMapper.class));
            assertThatThrownBy(()->f.tx(()->svc.create(original.getId(),request("audit")))).hasMessageContaining("审计");
            assertThat(f.scalar("SELECT COUNT(*) FROM @pi")).isEqualTo(1);
            assertThat(f.scalar("SELECT next_revision FROM @pi_series")).isEqualTo(2);
            assertThat(f.scalar("SELECT COUNT(*) FROM @pi_revision_request")).isZero();
            ReflectionTestUtils.setField(svc,"logs",logs);
            var start=new CountDownLatch(1);Callable<PiView> work=()->{start.await();return f.tx(()->svc.create(original.getId(),request("concurrent")));};
            var a=pool.submit(work);var b=pool.submit(work);start.countDown();
            assertThat(a.get(15,TimeUnit.SECONDS).getId()).isEqualTo(b.get(15,TimeUnit.SECONDS).getId());
            assertThat(f.scalar("SELECT COUNT(*) FROM @pi")).isEqualTo(2);
            assertThat(f.scalar("SELECT COUNT(*) FROM @operation_log WHERE business_type='PI_REVISION_CREATE'")).isEqualTo(1);
        }
    }
    @Test void confirmationRevalidatesLegacyDraftCurrencyAndIdentityBeforeAnyPublication() throws Exception {
        try(var f=new SkuCommitmentMysqlTest.Fixture(true)) {
            var original=PiPaymentCorrectionMysqlTest.paid(f);var svc=revisions(f);
            var draft=f.tx(()->svc.create(original.getId(),request("legacy")));
            f.exec("UPDATE @pi SET currency_code='USD' WHERE id="+draft.getId());
            assertThatThrownBy(()->f.tx(()->f.pis.confirm(draft.getId()))).hasMessageContaining("币种一致");
            assertThat(f.tx(()->f.pis.get(original.getId())).getStatus()).isEqualTo("RELEASED");
            assertThat(f.scalar("SELECT current_pi_id FROM @pi_series")).isEqualTo(original.getId());
            assertThat(f.tx(()->f.pis.get(draft.getId())).getStatus()).isEqualTo("DRAFT");
        }
    }
}
