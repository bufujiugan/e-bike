package com.bike.server.stock;

import com.bike.server.common.*;
import com.bike.server.lock.*;
import com.bike.server.order.*;
import com.bike.server.pi.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named="BIKE_MYSQL_TEST_URL",matches=".+")
class PaymentPriorityMysqlTest {
    @Test void overbookedLocksConvertAndDepositCancelsUnpaidPiWithVisibleReason() throws Exception {
        try(var f=new SkuCommitmentMysqlTest.Fixture(true)) {
            var first=pi(f,"first",1,1);var second=pi(f,"second",2,1);
            var extra=f.tx(()->f.locks.create(new SkuLockRequest("overbook",2L,null,null,null,List.of(new SkuLockRequest.Item(10,6))))).getFirst();
            install(f,null);
            var paid=f.tx(()->f.pis.recordPayment(first.getId(),payment("deposit",50)));
            assertThat(paid.getPaidAmount()).isEqualByComparingTo("50");
            assertThat(paid.getFulfillment()).allSatisfy(line->assertThat(line.reservationStatus()).isEqualTo("PAID_HELD"));
            var cancelled=f.tx(()->f.pis.get(second.getId()));
            assertThat(cancelled.getStatus()).isEqualTo("CANCELLED");
            assertThat(cancelled.getRemark()).contains("其他客户已付款优先占库");
            var order=f.orders.selectById(second.getFulfillment().getFirst().orderId());
            assertThat(order.getStatus()).isEqualTo("CANCELLED");
            assertThat(order.getRemark()).contains("剩余库存不足");
            assertThat(f.lockMapper.selectById(extra.getId()).getStatus()).isEqualTo("RELEASED");
            long audits=f.session.selectOne("paymentPriorityFixture.cancellationAuditCount");
            f.tx(()->f.pis.recordPayment(first.getId(),payment("deposit",50)));
            assertThat((Long)f.session.selectOne("paymentPriorityFixture.paymentCount")).isEqualTo(1);
            assertThat((Long)f.session.selectOne("paymentPriorityFixture.cancellationAuditCount")).isEqualTo(audits);
        }
    }

    @Test void partialReceiptProtectsCustomerAndDoesNotTriggerCompetition() throws Exception {
        try(var f=new SkuCommitmentMysqlTest.Fixture(true)) {
            var first=pi(f,"first",1,1);var partial=pi(f,"partial",2,1);var unpaid=pi(f,"unpaid",2,1);
            install(f,null);
            f.tx(()->f.pis.recordPayment(partial.getId(),payment("partial",10)));
            assertThat(f.tx(()->f.pis.get(unpaid.getId())).getStatus()).isEqualTo("CONFIRMED");
            f.tx(()->f.pis.recordPayment(first.getId(),payment("deposit",50)));
            assertThat(f.tx(()->f.pis.get(partial.getId())).getStatus()).isEqualTo("CONFIRMED");
            assertThat(f.tx(()->f.pis.get(unpaid.getId())).getStatus()).isEqualTo("CANCELLED");
        }
    }

    @Test void oneSkuShortageCancelsWholeMultiSkuPiAndEveryChild() throws Exception {
        try(var f=new SkuCommitmentMysqlTest.Fixture(true)) {
            var first=pi(f,"first",1,1);var multi=pi(f,"multi",2,1,10,11);
            install(f,null);
            f.tx(()->f.pis.recordPayment(first.getId(),payment("deposit",50)));
            assertThat(f.tx(()->f.pis.get(multi.getId())).getStatus()).isEqualTo("CANCELLED");
            for(var line:multi.getFulfillment()) {
                assertThat(f.orders.selectById(line.orderId()).getStatus()).isEqualTo("CANCELLED");
                assertThat(f.inventory.reservationItems(line.orderId()).getFirst().closedQty()).isEqualTo(1);
            }
        }
    }

    @Test void insufficientStockPreservesReceiptButDoesNotCancelAnyone() throws Exception {
        try(var f=new SkuCommitmentMysqlTest.Fixture(true)) {
            var tooLarge=pi(f,"large",1,2);var other=pi(f,"other",2,1);
            install(f,null);
            var paid=f.tx(()->f.pis.recordPayment(tooLarge.getId(),payment("deposit",50)));
            assertThat(paid.getPaidAmount()).isEqualByComparingTo("50");
            assertThat(paid.getFulfillment()).allSatisfy(line->assertThat(line.reservationStatus()).isEqualTo("TIMED_ACTIVE"));
            assertThat(f.tx(()->f.pis.get(other.getId())).getStatus()).isEqualTo("CONFIRMED");
            assertThat((Long)f.session.selectOne("paymentPriorityFixture.cancellationAuditCount")).isZero();
        }
    }

    @Test void auditFailureRollsBackReceiptPaidHoldAndAllCancellations() throws Exception {
        try(var f=new SkuCommitmentMysqlTest.Fixture(true)) {
            var first=pi(f,"first",1,1);var other=pi(f,"other",2,1);
            install(f,mock(OperationLogMapper.class));
            assertThatThrownBy(()->f.tx(()->f.pis.recordPayment(first.getId(),payment("deposit",50)))).isInstanceOf(BusinessException.class);
            assertThat((Long)f.session.selectOne("paymentPriorityFixture.paymentCount")).isZero();
            assertThat(f.tx(()->f.pis.get(other.getId())).getStatus()).isEqualTo("CONFIRMED");
            assertThat(f.reservations.selectForUpdate(first.getFulfillment().getFirst().orderId()).holdMode()).isEqualTo("TIMED");
            assertThat(f.reservations.selectForUpdate(other.getFulfillment().getFirst().orderId()).holdMode()).isEqualTo("TIMED");
        }
    }

    @Test void concurrentDepositsForLastSkuProduceOnePaidHoldAndOneCancellation() throws Exception {
        try(var f=new SkuCommitmentMysqlTest.Fixture(true);var pool=Executors.newFixedThreadPool(2)) {
            var first=pi(f,"first",1,1);var other=pi(f,"other",2,1);
            install(f,null);
            var start=new CountDownLatch(1);var futures=new ArrayList<Future<String>>();
            for(var pi:List.of(first,other))futures.add(pool.submit(()->{
                start.await(10,TimeUnit.SECONDS);
                try {f.tx(()->f.pis.recordPayment(pi.getId(),payment("race-"+pi.getId(),50)));return "PAID";}
                catch(BusinessException error){return error.getCode();}
            }));
            start.countDown();
            var results=new ArrayList<String>();for(var future:futures)results.add(future.get(30,TimeUnit.SECONDS));
            assertThat(results).containsExactlyInAnyOrder("PAID","INVALID_STATUS_TRANSITION");
            assertThat(f.inventory.selectFulfillmentSnapshot(10,0).committedQty()).isEqualTo(1);
            assertThat((Long)f.session.selectOne("paymentPriorityFixture.paymentCount")).isEqualTo(1);
        }
    }

    @Test void newOverbookingDoesNotBlockPaidOrderShipmentCapacity() throws Exception {
        try(var f=new SkuCommitmentMysqlTest.Fixture(true)) {
            var first=pi(f,"first",1,1);install(f,null);
            f.tx(()->f.pis.recordPayment(first.getId(),payment("deposit",50)));
            f.tx(()->f.locks.create(new SkuLockRequest("later-demand",2L,null,null,null,List.of(new SkuLockRequest.Item(10,100)))));
            long orderId=first.getFulfillment().getFirst().orderId();
            assertThat(f.inventory.snapshot(10,orderId).availableForOrder()).isNegative();
            var guard=new OrderInventoryGuard(f.reservations);
            assertThatCode(()->guard.requireShipment(f.orders.selectById(orderId),1)).doesNotThrowAnyException();
            assertThatCode(()->new SkuInventoryGuard(f.inventory).requireVehicle(orderId,100)).doesNotThrowAnyException();
        }
    }

    static PiPaymentRequest payment(String key,int amount){return new PiPaymentRequest(key,BigDecimal.valueOf(amount),"CNY",null,null,null);}

    static PiView pi(SkuCommitmentMysqlTest.Fixture f,String key,long customer,int quantity,long... skuIds) {
        if(skuIds.length==0)skuIds=new long[]{10};
        var items=new ArrayList<PiItemRequest>();var lockItems=new ArrayList<SkuLockRequest.Item>();
        for(long sku:skuIds){items.add(new PiItemRequest(sku,quantity,new BigDecimal("100"),null,null));lockItems.add(new SkuLockRequest.Item(sku,quantity));}
        var locks=f.tx(()->f.locks.create(new SkuLockRequest("lock-"+key,customer,null,null,null,lockItems)));
        var doc=new PiDocumentSnapshot("卖方",null,null,null,null,null,null,null,"收50保留/收100发货","客户自提","DOMESTIC","1");
        var request=new PiCreateRequest(customer,null,null,"DOMESTIC","CNY",null,null,null,null,new BigDecimal("50"),new BigDecimal("100"),null,doc,items);
        var draft=f.tx(()->f.piConversion.convert(new PiConversionRequest("pi-"+key,locks.stream().map(lock->new PiConversionRequest.Source(lock.getId(),quantity)).toList(),request)));
        return f.tx(()->f.pis.confirm(draft.getId()));
    }

    static void install(SkuCommitmentMysqlTest.Fixture f,OperationLogMapper failingAudit) {
        f.session.update("paymentPriorityFixture.prepareDocuments");
        var json=new ObjectMapper().findAndRegisterModules();
        var pis=f.session.getMapper(PiMapper.class);var warehouses=f.session.getMapper(PiBusinessMapper.class);
        var logs=f.session.getMapper(OperationLogMapper.class);
        var priority=new PaymentPriorityServiceImpl(f.session.getMapper(PaymentPriorityMapper.class),pis,f.piSeries,warehouses,f.orders,f.reservations,f.inventory,f.lockMapper,failingAudit==null?logs:failingAudit,json,f.guard);
        var workflow=mock(PiWorkflowMapper.class);
        when(workflow.selectSteps(anyLong())).thenReturn(List.of(new PiWorkflowStepRecord(1,"DOCUMENTS_READY","资料","COMPLETED",1L,null,null,
                "{\"contractNo\":\"TEST\",\"contractReady\":true,\"paymentTableReady\":true}")));
        var business=new PiBusinessServiceImpl(warehouses,pis,f.piSeries,workflow,f.orders,f.reservations,f.inventory,logs,json);
        ReflectionTestUtils.setField(business,"paymentPriority",priority);
        ReflectionTestUtils.setField(f.pis,"business",business);
    }
}
