package com.bike.server.stock;

import static org.assertj.core.api.Assertions.*;
import com.bike.server.common.BusinessException;
import com.bike.server.lock.SkuLockRequest;
import com.bike.server.pi.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named="BIKE_MYSQL_TEST_URL",matches=".+")
class PiConversionMysqlTest {
    @Test void differentModelsConvertAndPayTogetherButRemainExactSkuOrders() throws Exception {
        try(var f=new SkuCommitmentMysqlTest.Fixture(true)) {
            var request=request(f);
            PiView draft=f.tx(()->f.piConversion.convert(request));
            assertThat(draft.getSourceOrderId()).isNull();assertThat(draft.getSeriesId()).isNotNull();
            assertThat(draft.getFulfillment()).hasSize(2).allSatisfy(line->{assertThat(line.status()).isEqualTo("DRAFT");assertThat(line.remainingQty()).isEqualTo(1);});
            assertThat(f.scalar("SELECT COUNT(*) FROM @sales_order")).isEqualTo(2);
            assertThat(f.tx(()->f.piConversion.convert(request)).getId()).isEqualTo(draft.getId());
            long red=order(draft,10),green=order(draft,12);
            code(()->f.tx(()->f.stock.stockOut(new StockOutRequest(1L,red,List.of("QR100")))),"INVALID_STATUS_TRANSITION");
            f.tx(()->f.pis.confirm(draft.getId()));
            code(()->f.tx(()->f.stock.stockOut(new StockOutRequest(1L,red,List.of("QR100")))),"PI_SHIPMENT_NOT_RELEASED");
            var paid=f.tx(()->f.pis.recordPayment(draft.getId(),new PiPaymentRequest("receipt-one",new BigDecimal("200"),"CNY",null,null,null)));
            assertThat(paid.getPaidAmount()).isEqualByComparingTo("200");
            assertThat(paid.getFulfillment()).allSatisfy(line->assertThat(line.reservationStatus()).isEqualTo("PAID_HELD"));
            assertThat(f.scalar("SELECT COUNT(*) FROM @pi_payment")).isEqualTo(1);
            code(()->f.tx(()->f.stock.stockOut(new StockOutRequest(1L,green,List.of("QR120")))),"PI_SHIPMENT_NOT_RELEASED");
            f.tx(()->f.pis.release(draft.getId(),new PiReleaseRequest("核实200元")));
            f.tx(()->f.stock.stockOut(new StockOutRequest(1L,red,List.of("QR100"))));
            f.tx(()->f.stock.stockOut(new StockOutRequest(1L,green,List.of("QR120"))));
            assertThat(f.tx(()->f.pis.get(draft.getId())).getFulfillment()).allSatisfy(line->{assertThat(line.shippedQty()).isEqualTo(1);assertThat(line.remainingQty()).isZero();});
        }
    }
    @Test void latePiValidationFailureRollsBackEveryOrderLockAndAudit() throws Exception {
        try(var f=new SkuCommitmentMysqlTest.Fixture(true)) {
            var original=request(f);var p=original.pi();
            var invalid=new PiCreateRequest(p.customerId(),p.agentId(),null,p.tradeType(),p.currencyCode(),null,null,null,null,
                    new BigDecimal("999"),new BigDecimal("999"),null,p.document(),p.items());
            long audits=f.scalar("SELECT COUNT(*) FROM @operation_log");
            code(()->f.tx(()->f.piConversion.convert(new PiConversionRequest(original.requestId(),original.sources(),invalid))),"AMOUNT_INVALID");
            assertThat(f.scalar("SELECT COUNT(*) FROM @sales_order")).isZero();
            assertThat(f.scalar("SELECT SUM(converted_qty) FROM @inventory_lock")).isZero();
            assertThat(f.scalar("SELECT COUNT(*) FROM @pi")).isZero();
            assertThat(f.scalar("SELECT COUNT(*) FROM @operation_log")).isEqualTo(audits);
            assertThat(f.tx(()->f.piConversion.convert(original)).getFulfillment()).hasSize(2);
        }
    }
    @Test void retriesAreDurableAndConcurrentRequestsCannotConsumeTheSameLocksTwice() throws Exception {
        try(var f=new SkuCommitmentMysqlTest.Fixture(true);var pool=Executors.newFixedThreadPool(2)) {
            var request=request(f);var start=new CountDownLatch(1);
            Callable<PiView> work=()->{start.await();return f.tx(()->f.piConversion.convert(request));};
            var one=pool.submit(work);var two=pool.submit(work);start.countDown();
            assertThat(one.get(15,TimeUnit.SECONDS).getId()).isEqualTo(two.get(15,TimeUnit.SECONDS).getId());
            assertThat(f.scalar("SELECT COUNT(*) FROM @pi_conversion_request")).isEqualTo(1);
            assertThat(f.scalar("SELECT COUNT(*) FROM @sales_order")).isEqualTo(2);
            code(()->f.tx(()->f.piConversion.convert(new PiConversionRequest("different-request",request.sources(),request.pi()))),"INVENTORY_LOCK_CONFLICT");
            var changed=new PiConversionRequest(request.requestId(),List.of(new PiConversionRequest.Source(request.sources().getFirst().lockId(),2)),request.pi());
            code(()->f.tx(()->f.piConversion.convert(changed)),"PI_CONVERSION_CONFLICT");
        }
    }
    @Test void linkedDraftCannotSwapSkuOrCustomerAndCancellationReleasesAllCommitments() throws Exception {
        try(var f=new SkuCommitmentMysqlTest.Fixture(true)) {
            var request=request(f);var pi=f.tx(()->f.piConversion.convert(request));var p=request.pi();
            var swap=new PiCreateRequest(1L,null,null,"DOMESTIC","CNY",null,null,null,null,p.depositRequiredAmount(),p.shipmentRequiredAmount(),null,p.document(),List.of(item(11),item(12)));
            code(()->f.tx(()->f.pis.update(pi.getId(),swap)),"ORDER_SKU_MISMATCH");
            var customer=new PiCreateRequest(2L,null,null,"DOMESTIC","CNY",null,null,null,null,p.depositRequiredAmount(),p.shipmentRequiredAmount(),null,p.document(),p.items());
            code(()->f.tx(()->f.pis.update(pi.getId(),customer)),"LOCK_CUSTOMER_MISMATCH");
            var cancelled=f.tx(()->f.pis.cancel(pi.getId(),new PiCancelRequest("客户取消")));
            assertThat(cancelled.getFulfillment()).allSatisfy(line->{assertThat(line.closedQty()).isEqualTo(1);assertThat(line.remainingQty()).isZero();});
            assertThat(f.reservations.snapshot(4,0).getCommittedQty()).isZero();assertThat(f.reservations.snapshot(5,0).getCommittedQty()).isZero();
        }
    }
    static PiConversionRequest request(SkuCommitmentMysqlTest.Fixture f) {
        f.addVehicle(120,12);f.exec("UPDATE @vehicle SET model_id=5 WHERE id=120");
        var locks=f.tx(()->f.locks.create(new SkuLockRequest("source-locks",1L,null,null,null,List.of(new SkuLockRequest.Item(10,1),new SkuLockRequest.Item(12,1)))));
        var doc=new PiDocumentSnapshot("卖方",null,null,null,null,null,null,null,"收50保留/收200发货","客户自提","DOMESTIC","1");
        var pi=new PiCreateRequest(1L,null,null,"DOMESTIC","CNY",null,null,null,null,new BigDecimal("50"),new BigDecimal("200"),null,doc,List.of(item(10),item(12)));
        return new PiConversionRequest("convert-request",locks.stream().map(l->new PiConversionRequest.Source(l.getId(),1)).toList(),pi);
    }
    private static PiItemRequest item(long id){return new PiItemRequest(id,1,new BigDecimal("100"),null,null);}
    private static long order(PiView pi,long sku){return pi.getFulfillment().stream().filter(line->Objects.equals(line.skuId(),sku)).findFirst().orElseThrow().orderId();}
    private static void code(Supplier<?> work,String code){assertThatThrownBy(work::get).isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.getCode()).isEqualTo(code));}
}
