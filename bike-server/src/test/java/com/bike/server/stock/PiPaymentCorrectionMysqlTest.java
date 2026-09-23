package com.bike.server.stock;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.bike.server.common.*;
import com.bike.server.pi.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.util.ReflectionTestUtils;

@EnabledIfEnvironmentVariable(named="BIKE_MYSQL_TEST_URL",matches=".+")
class PiPaymentCorrectionMysqlTest {
    static PiPaymentRequest payment(String id,String method,String note) {return new PiPaymentRequest(id,new BigDecimal("200.00"),"CNY",null,null,"核对到账",method,note);}
    static PiView paid(SkuCommitmentMysqlTest.Fixture f) {
        var request=PiConversionMysqlTest.request(f);var pi=f.tx(()->f.piConversion.convert(request));
        f.tx(()->f.pis.confirm(pi.getId()));f.tx(()->f.pis.recordPayment(pi.getId(),payment("first","BANK_TRANSFER",null)));
        return f.tx(()->f.pis.release(pi.getId(),new PiReleaseRequest("核对到账")));
    }
    @Test void methodParticipatesInIdempotencyAndReversalBlocksShippingWithoutReleasingCommitments()throws Exception {
        try(var f=new SkuCommitmentMysqlTest.Fixture(true)) {
            var pi=paid(f);long id=pi.getId();var receipt=pi.getPayments().getFirst();
            assertThat(receipt.paymentMethod()).isEqualTo("BANK_TRANSFER");assertThat(receipt.recordedAt()).isNotNull();
            assertThatThrownBy(()->f.tx(()->f.pis.recordPayment(id,payment("first","CASH",null)))).hasMessageContaining("不同收款内容");
            assertThatThrownBy(()->f.tx(()->f.pis.recordPayment(id,payment("other","OTHER",null)))).hasMessageContaining("其他收款方式");
            var request=new PiPaymentReverseRequest("reverse",1L,"重复登记，核对银行流水后更正");
            var reversed=f.tx(()->f.pis.reversePayment(id,receipt.id(),request));
            assertThat(reversed.getPaidAmount()).isEqualByComparingTo("0");assertThat(reversed.getStatus()).isEqualTo("CONFIRMED");assertThat(reversed.getReleaseStatus()).isEqualTo("BLOCKED");
            assertThat(reversed.getFulfillment()).allSatisfy(line->assertThat(line.reservationStatus()).isEqualTo("PAID_HELD"));
            assertThat(reversed.getPayments().getFirst().status()).isEqualTo("REVERSED");assertThat(reversed.getPayments().getFirst().amount()).isEqualByComparingTo("200");
            assertThatThrownBy(()->f.tx(()->f.stock.stockOut(new StockOutRequest(1L,pi.getFulfillment().getFirst().orderId(),List.of("QR100"))))).hasMessageContaining("放行");
            f.tx(()->f.pis.recordPayment(id,payment("replacement","OTHER","客户柜台交款")));
            assertThat(f.tx(()->f.pis.get(id)).getReleaseStatus()).isEqualTo("BLOCKED");
            f.tx(()->f.pis.release(id,new PiReleaseRequest("更正后重新核对")));
            assertThat(f.tx(()->f.pis.reversePayment(id,receipt.id(),request)).getReleaseStatus()).isEqualTo("RELEASED");
            assertThatThrownBy(()->f.tx(()->f.pis.reversePayment(id,receipt.id(),new PiPaymentReverseRequest("reverse",1L,"改变原因")))).hasMessageContaining("已撤回");
        }
    }
    @Test void concurrentReversalsCommitOnceAndAuditFailureRollsBackEveryState()throws Exception {
        try(var f=new SkuCommitmentMysqlTest.Fixture(true);var pool=Executors.newFixedThreadPool(2)) {
            var pi=paid(f);long paymentId=pi.getPayments().getFirst().id();
            var logs=ReflectionTestUtils.getField(f.pis,"logs");ReflectionTestUtils.setField(f.pis,"logs",mock(OperationLogMapper.class));
            var request=new PiPaymentReverseRequest("one-reversal",1L,"核对重复到账");
            assertThatThrownBy(()->f.tx(()->f.pis.reversePayment(pi.getId(),paymentId,request))).isInstanceOf(BusinessException.class);
            ReflectionTestUtils.setField(f.pis,"logs",logs);
            var unchanged=f.tx(()->f.pis.get(pi.getId()));assertThat(unchanged.getStatus()).isEqualTo("RELEASED");assertThat(unchanged.getPayments().getFirst().status()).isEqualTo("CONFIRMED");assertThat(unchanged.getPaidAmount()).isEqualByComparingTo("200");
            var start=new CountDownLatch(1);Callable<PiView> task=()->{start.await();return f.tx(()->f.pis.reversePayment(pi.getId(),paymentId,request));};
            var a=pool.submit(task);var b=pool.submit(task);start.countDown();
            assertThat(a.get(15,TimeUnit.SECONDS).getPayments().getFirst().revision()).isEqualTo(2);
            assertThat(b.get(15,TimeUnit.SECONDS).getPayments().getFirst().revision()).isEqualTo(2);
            assertThat(f.scalar("SELECT COUNT(*) FROM @operation_log WHERE action='撤回PI错误收款登记'")).isEqualTo(1);
        }
    }
    @Test void reversalAfterShipmentPreservesVehicleStockAndPublishedSnapshot()throws Exception {
        try(var f=new SkuCommitmentMysqlTest.Fixture(true)) {
            var pi=paid(f);long order=pi.getFulfillment().stream().filter(x->x.skuId()==10).findFirst().orElseThrow().orderId();
            f.tx(()->f.stock.stockOut(new StockOutRequest(1L,order,List.of("QR100"))));
            long physical=f.scalar("SELECT COUNT(*) FROM @vehicle WHERE status=2");
            var result=f.tx(()->f.pis.reversePayment(pi.getId(),pi.getPayments().getFirst().id(),new PiPaymentReverseRequest("post-shipping",1L,"实际发运后发现重复录入")));
            assertThat(result.getFulfillment().stream().filter(x->x.orderId()==order).findFirst().orElseThrow().shippedQty()).isEqualTo(1);
            assertThat(f.scalar("SELECT COUNT(*) FROM @vehicle WHERE status=2")).isEqualTo(physical);
            assertThat(f.scalar("SELECT COUNT(*) FROM @stock_out_record WHERE status='COMPLETED'")).isEqualTo(1);
            assertThat(result.getDocument()).isEqualTo(pi.getDocument());assertThat(result.getTotalAmount()).isEqualByComparingTo(pi.getTotalAmount());
        }
    }
    @Test void historicalReceiptCorrectionUpdatesCurrentRevisionAndShowsOriginalReceiptIdentity()throws Exception {
        try(var f=new SkuCommitmentMysqlTest.Fixture(true)) {
            var pi=paid(f);long oldId=pi.getId(),receiptId=pi.getPayments().getFirst().id();
            f.exec("INSERT INTO @pi(series_id,previous_revision_id,pi_no,revision,customer_id,customer_name,customer_code,currency_code,trade_type,status,payment_status,release_status,subtotal,discount_amount,tax_amount,freight_amount,total_amount,deposit_required_amount,shipment_required_amount,document_snapshot) SELECT series_id,id,CONCAT(pi_no,'-R2'),2,customer_id,customer_name,customer_code,currency_code,trade_type,'RELEASED','PAID','RELEASED',subtotal,discount_amount,tax_amount,freight_amount,total_amount,deposit_required_amount,shipment_required_amount,document_snapshot FROM @pi WHERE id="+oldId);
            long current=f.scalar("SELECT MAX(id) FROM @pi");
            f.exec("UPDATE @pi_series SET current_pi_id="+current+" WHERE id="+pi.getSeriesId());f.exec("UPDATE @pi SET status='SUPERSEDED' WHERE id="+oldId);
            var view=f.tx(()->f.pis.get(current));assertThat(view.getPayments()).hasSize(1);assertThat(view.getPayments().getFirst().piId()).isEqualTo(oldId);
            f.tx(()->f.pis.reversePayment(current,receiptId,new PiPaymentReverseRequest("historical",1L,"旧版本重复到账")));
            assertThat(f.tx(()->f.pis.get(current)).getReleaseStatus()).isEqualTo("BLOCKED");
            assertThat(f.tx(()->f.pis.get(oldId)).getStatus()).isEqualTo("SUPERSEDED");
            assertThat(f.scalar("SELECT COUNT(*) FROM @pi WHERE payment_status='UNPAID'")).isEqualTo(2);
            assertThat(f.tx(()->f.pis.get(current)).getPayments().getFirst().status()).isEqualTo("REVERSED");
        }
    }
}
