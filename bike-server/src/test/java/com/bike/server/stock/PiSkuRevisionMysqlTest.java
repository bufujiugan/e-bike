package com.bike.server.stock;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.bike.server.common.*;
import com.bike.server.lock.*;
import com.bike.server.pi.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.util.ReflectionTestUtils;

@EnabledIfEnvironmentVariable(named="BIKE_MYSQL_TEST_URL",matches=".+")
class PiSkuRevisionMysqlTest {
    static PiView revision(SkuCommitmentMysqlTest.Fixture f,PiView original,String key) {
        return f.tx(()->PiRevisionLifecycleMysqlTest.revisions(f).create(original.getId(),new PiRevisionRequest(key,original.getRevision(),"核对新增和移除商品")));
    }
    static InventoryLockView blueLock(SkuCommitmentMysqlTest.Fixture f,String key,long customer) {
        return f.tx(()->f.locks.create(new SkuLockRequest(key,customer,null,null,null,List.of(new SkuLockRequest.Item(11,1))))).getFirst();
    }
    static PiCreateRequest edit(PiView pi,List<PiItemRequest> items,List<PiConversionRequest.Source> sources) {
        return new PiCreateRequest(pi.getCustomerId(),pi.getAgentId(),pi.getSourceOrderId(),pi.getTradeType(),pi.getCurrencyCode(),pi.getPlannedShipDate(),
                BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO,new BigDecimal("50"),new BigDecimal("100"),"SKU修订",pi.getDocument(),items,BigDecimal.ZERO,sources);
    }
    static PiItemRequest item(long sku) {return new PiItemRequest(sku,1,new BigDecimal("100"),null,null);}
    static List<PiConversionRequest.Source> source(InventoryLockView lock) {return List.of(new PiConversionRequest.Source(lock.getId(),1));}
    static List<PiItemRequest> replacement() {return List.of(item(10),item(11));}

    @Test void replacingUnshippedSkuCommitsSourcesOnlyOnPublicationAndShipsRemainingLines()throws Exception {
        try(var f=new SkuCommitmentMysqlTest.Fixture(true)) {
            var original=PiPaymentCorrectionMysqlTest.paid(f);var lock=blueLock(f,"blue",1);
            var draft=revision(f,original,"replace-green");
            var saved=f.tx(()->f.pis.update(draft.getId(),edit(draft,replacement(),source(lock))));
            assertThat(saved.getRevisionSources()).singleElement().satisfies(s->{assertThat(s.orderId()).isNull();assertThat(s.expiresAt()).isEqualTo(lock.getExpireTime());});
            assertThat(f.scalar("SELECT COUNT(*) FROM @sales_order")).isEqualTo(2);
            assertThat(f.inventory.snapshot(12,0).committedQty()).isEqualTo(1);
            assertThat(f.lockMapper.selectById(lock.getId()).getConvertedQty()).isZero();
            var published=f.tx(()->f.pis.confirm(saved.getId()));
            assertThat(published.getRevisionSources()).singleElement().satisfies(s->assertThat(s.orderId()).isPositive());
            var blue=published.getFulfillment().stream().filter(l->l.skuId()==11).findFirst().orElseThrow();
            var removed=published.getFulfillment().stream().filter(l->l.skuId()==12).findFirst().orElseThrow();
            assertThat(removed.status()).isEqualTo("CANCELLED");assertThat(removed.closedQty()).isEqualTo(1);
            assertThat(f.inventory.snapshot(12,0).committedQty()).isZero();
            assertThat(f.inventory.snapshot(11,0).committedQty()).isEqualTo(1);
            assertThat(f.reservations.selectForUpdate(blue.orderId()).expiresAt()).isEqualTo(lock.getExpireTime());
            assertThat(f.lockMapper.selectById(lock.getId()).getConvertedQty()).isEqualTo(1);
            assertThat(f.tx(()->f.pis.get(original.getId())).getItems()).extracting(PiItemView::getSkuId).containsExactly(10L,12L);
            f.tx(()->f.pis.release(saved.getId(),new PiReleaseRequest("核对替换后数量")));
            f.tx(()->f.stock.stockOut(new StockOutRequest(1L,blue.orderId(),List.of("QR101"))));
            assertThat(f.orders.selectById(blue.orderId()).getStatus()).isEqualTo("SHIPPED");
            assertThat(f.scalar("SELECT COUNT(*) FROM @pi_payment")).isEqualTo(1);
        }
    }

    @Test void missingForeignExpiredAndOverallocatedSourcesAreRejectedWithoutChangingEffectiveOrders()throws Exception {
        try(var f=new SkuCommitmentMysqlTest.Fixture(true)) {
            var original=PiPaymentCorrectionMysqlTest.paid(f);var draft=revision(f,original,"invalid-source");
            assertThatThrownBy(()->f.tx(()->f.pis.update(draft.getId(),edit(draft,replacement(),List.of())))).hasMessageContaining("足额");
            var foreign=blueLock(f,"foreign",2);
            assertThatThrownBy(()->f.tx(()->f.pis.update(draft.getId(),edit(draft,replacement(),source(foreign))))).hasMessageContaining("同一客户");
            f.exec("UPDATE @inventory_lock SET customer_id=1 WHERE id="+foreign.getId());
            assertThatThrownBy(()->f.tx(()->f.pis.update(draft.getId(),edit(draft,replacement(),List.of(new PiConversionRequest.Source(foreign.getId(),2)))))).hasMessageContaining("剩余数量");
            f.tx(()->f.pis.update(draft.getId(),edit(draft,replacement(),source(foreign))));
            f.exec("UPDATE @inventory_lock SET expire_time=DATE_SUB(NOW(3),INTERVAL 1 DAY) WHERE id="+foreign.getId());
            assertThatThrownBy(()->f.tx(()->f.pis.confirm(draft.getId()))).hasMessageContaining("已到期");
            assertThat(f.scalar("SELECT current_pi_id FROM @pi_series")).isEqualTo(original.getId());
            assertThat(f.inventory.snapshot(12,0).committedQty()).isEqualTo(1);
            assertThat(f.scalar("SELECT COUNT(*) FROM @sales_order")).isEqualTo(2);
            assertThat(f.scalar("SELECT COUNT(*) FROM @pi_revision_source WHERE order_id IS NOT NULL")).isZero();
        }
    }

    @Test void auditFailureAfterSourceConversionRollsBackRemovalNewOrderLinkAndConvertedQuantity()throws Exception {
        try(var f=new SkuCommitmentMysqlTest.Fixture(true)) {
            var original=PiPaymentCorrectionMysqlTest.paid(f);var lock=blueLock(f,"rollback",1);var draft=revision(f,original,"rollback");
            f.tx(()->f.pis.update(draft.getId(),edit(draft,replacement(),source(lock))));
            var quantity=ReflectionTestUtils.getField(f.pis,"quantityRevisions");
            var originalLog=(OperationLogMapper)ReflectionTestUtils.getField(quantity,"logs");
            var broken=mock(OperationLogMapper.class);
            when(broken.insert(anyString(),anyString(),anyString(),any(),anyString(),anyString(),anyString(),any(),anyString()))
                    .thenAnswer(call->"确认PI新增SKU来源".equals(call.getArgument(2)) ? 0 : originalLog.insert(call.getArgument(0),call.getArgument(1),call.getArgument(2),call.getArgument(3),call.getArgument(4),call.getArgument(5),call.getArgument(6),call.getArgument(7),call.getArgument(8)));
            ReflectionTestUtils.setField(quantity,"logs",broken);
            assertThatThrownBy(()->f.tx(()->f.pis.confirm(draft.getId()))).hasMessageContaining("新增SKU审计");
            assertThat(f.scalar("SELECT COUNT(*) FROM @sales_order")).isEqualTo(2);
            assertThat(f.scalar("SELECT COUNT(*) FROM @pi_order_link")).isEqualTo(2);
            assertThat(f.inventory.snapshot(12,0).committedQty()).isEqualTo(1);
            assertThat(f.lockMapper.selectById(lock.getId()).getConvertedQty()).isZero();
            assertThat(f.scalar("SELECT COUNT(*) FROM @pi_revision_source WHERE order_id IS NOT NULL")).isZero();
            ReflectionTestUtils.setField(quantity,"logs",originalLog);
            assertThat(f.tx(()->f.pis.confirm(draft.getId())).getStatus()).isEqualTo("CONFIRMED");
        }
    }

    @Test void concurrentConfirmConsumesNewSourceOnceAndCancelledDraftNeverConverts()throws Exception {
        try(var f=new SkuCommitmentMysqlTest.Fixture(true);var pool=Executors.newFixedThreadPool(2)) {
            var original=PiPaymentCorrectionMysqlTest.paid(f);var lock=blueLock(f,"concurrent",1);var abandoned=revision(f,original,"abandoned");
            f.tx(()->f.pis.update(abandoned.getId(),edit(abandoned,replacement(),source(lock))));
            f.tx(()->f.pis.cancel(abandoned.getId(),new PiCancelRequest("重新核对")));
            assertThat(f.lockMapper.selectById(lock.getId()).getConvertedQty()).isZero();
            var draft=revision(f,original,"publish-once");f.tx(()->f.pis.update(draft.getId(),edit(draft,replacement(),source(lock))));
            var start=new CountDownLatch(1);
            Callable<Boolean> confirm=()->{start.await();try{f.tx(()->f.pis.confirm(draft.getId()));return true;}catch(BusinessException error){assertThat(error.getCode()).isEqualTo("INVALID_STATUS_TRANSITION");return false;}};
            var a=pool.submit(confirm);var b=pool.submit(confirm);start.countDown();
            assertThat(a.get(15,TimeUnit.SECONDS)^b.get(15,TimeUnit.SECONDS)).isTrue();
            assertThat(f.lockMapper.selectById(lock.getId()).getConvertedQty()).isEqualTo(1);
            assertThat(f.scalar("SELECT COUNT(*) FROM @sales_order")).isEqualTo(3);
            assertThat(f.scalar("SELECT COUNT(*) FROM @pi_revision_source WHERE order_id IS NOT NULL")).isEqualTo(1);
        }
    }

    @Test void removedSkuCanReturnThroughNewSourceWithoutReopeningClosedOrderAndShippedSkuCannotDisappear()throws Exception {
        try(var f=new SkuCommitmentMysqlTest.Fixture(true)) {
            var original=PiPaymentCorrectionMysqlTest.paid(f);var remove=revision(f,original,"remove-red");
            f.tx(()->f.pis.update(remove.getId(),edit(remove,List.of(item(12)),List.of())));
            var removed=f.tx(()->f.pis.confirm(remove.getId()));
            long oldRed=removed.getFulfillment().stream().filter(l->l.skuId()==10).findFirst().orElseThrow().orderId();
            var lock=f.tx(()->f.locks.create(new SkuLockRequest("return-red",1L,null,null,null,List.of(new SkuLockRequest.Item(10,1))))).getFirst();
            var restore=revision(f,removed,"restore-red");f.tx(()->f.pis.update(restore.getId(),edit(restore,List.of(item(10),item(12)),source(lock))));
            var restored=f.tx(()->f.pis.confirm(restore.getId()));
            assertThat(f.orders.selectById(oldRed).getStatus()).isEqualTo("CANCELLED");
            long newRed=restored.getRevisionSources().getFirst().orderId();assertThat(newRed).isNotEqualTo(oldRed);
            f.tx(()->f.pis.release(restore.getId(),new PiReleaseRequest("重新核对")));
            f.tx(()->f.stock.stockOut(new StockOutRequest(1L,newRed,List.of("QR100"))));
            var afterShipping=revision(f,f.tx(()->f.pis.get(restore.getId())),"remove-shipped");
            assertThatThrownBy(()->f.tx(()->f.pis.update(afterShipping.getId(),edit(afterShipping,List.of(item(12)),List.of())))).hasMessageContaining("实际已出库");
        }
    }

    @Test void changedDeadlineMustBeAcknowledgedAndCompetingConversionCannotBeConsumedAgain()throws Exception {
        try(var f=new SkuCommitmentMysqlTest.Fixture(true)) {
            var original=PiPaymentCorrectionMysqlTest.paid(f);var lock=blueLock(f,"changed-source",1);var draft=revision(f,original,"changed-source");
            f.tx(()->f.pis.update(draft.getId(),edit(draft,replacement(),source(lock))));
            f.exec("UPDATE @inventory_lock SET expire_time=DATE_ADD(expire_time,INTERVAL 1 HOUR) WHERE id="+lock.getId());
            assertThatThrownBy(()->f.tx(()->f.pis.confirm(draft.getId()))).isInstanceOfSatisfying(BusinessException.class,
                    e->assertThat(e.getCode()).isEqualTo("PI_REVISION_SOURCE_CHANGED"));
            assertThat(f.scalar("SELECT COUNT(*) FROM @sales_order")).isEqualTo(2);
            var acknowledged=f.tx(()->f.pis.update(draft.getId(),edit(draft,replacement(),source(lock))));
            assertThat(acknowledged.getRevisionSources().getFirst().expiresAt()).isEqualTo(lock.getExpireTime().plusHours(1));
            f.tx(()->f.conversion.convert(lock.getId(),new LockOrderRequest(1L,1,"competing-conversion",null)));
            assertThatThrownBy(()->f.tx(()->f.pis.confirm(draft.getId()))).isInstanceOfSatisfying(BusinessException.class,
                    e->assertThat(e.getCode()).isEqualTo("PI_REVISION_SOURCE_UNAVAILABLE"));
            assertThat(f.scalar("SELECT COUNT(*) FROM @sales_order")).isEqualTo(3);
            assertThat(f.inventory.snapshot(12,0).committedQty()).isEqualTo(1);
            assertThat(f.scalar("SELECT current_pi_id FROM @pi_series")).isEqualTo(original.getId());
        }
    }

    @Test void newSourceForShippedSkuAddsOnlyTheUnshippedDifference()throws Exception {
        try(var f=new SkuCommitmentMysqlTest.Fixture(true)) {
            var original=PiPaymentCorrectionMysqlTest.paid(f);
            long oldRed=original.getFulfillment().stream().filter(l->l.skuId()==10).findFirst().orElseThrow().orderId();
            f.tx(()->f.stock.stockOut(new StockOutRequest(1L,oldRed,List.of("QR100"))));
            f.addVehicle(130,10);
            var lock=f.tx(()->f.locks.create(new SkuLockRequest("red-increment",1L,null,null,null,List.of(new SkuLockRequest.Item(10,1))))).getFirst();
            var draft=revision(f,f.tx(()->f.pis.get(original.getId())),"red-increment");
            assertThatThrownBy(()->f.tx(()->f.pis.update(draft.getId(),edit(draft,List.of(item(10),item(12)),source(lock)))))
                    .hasMessageContaining("实际已出库");
            var lines=List.of(new PiItemRequest(10L,2,new BigDecimal("100"),null,null),item(12));
            f.tx(()->f.pis.update(draft.getId(),edit(draft,lines,source(lock))));
            var published=f.tx(()->f.pis.confirm(draft.getId()));
            var red=published.getFulfillment().stream().filter(l->l.skuId()==10).toList();
            assertThat(red).hasSize(2);assertThat(red.stream().mapToInt(PiFulfillmentLine::shippedQty).sum()).isEqualTo(1);
            assertThat(red.stream().mapToInt(PiFulfillmentLine::remainingQty).sum()).isEqualTo(1);
            assertThat(f.orders.selectById(oldRed).getStatus()).isEqualTo("SHIPPED");
            long newRed=published.getRevisionSources().getFirst().orderId();
            f.tx(()->f.pis.release(published.getId(),new PiReleaseRequest("核对追加红车")));
            f.tx(()->f.stock.stockOut(new StockOutRequest(1L,newRed,List.of("QR130"))));
            assertThat(f.orders.selectById(newRed).getStatus()).isEqualTo("SHIPPED");
        }
    }

    @Test void ordinaryConversionCannotPreclaimTheNewRevisionOrderAndArchiveFailureRollsBack()throws Exception {
        try(var f=new SkuCommitmentMysqlTest.Fixture(true)) {
            var original=PiPaymentCorrectionMysqlTest.paid(f);f.addVehicle(131,11);
            var lock=f.tx(()->f.locks.create(new SkuLockRequest("source-with-two",1L,null,null,null,List.of(new SkuLockRequest.Item(11,2))))).getFirst();
            var draft=revision(f,original,"preclaimed-request");
            f.tx(()->f.pis.update(draft.getId(),edit(draft,replacement(),source(lock))));
            var separate=f.tx(()->f.conversion.convert(lock.getId(),new LockOrderRequest(1L,1,"PI_REV_"+draft.getId()+"_"+lock.getId(),null)));
            var archives=mock(PiExportArchive.class);
            doThrow(new IllegalStateException("archive queue unavailable")).when(archives).enqueue(any(),eq("CONFIRMATION"));
            ReflectionTestUtils.setField(f.pis,"archives",archives);
            assertThatThrownBy(()->f.tx(()->f.pis.confirm(draft.getId()))).hasMessage("archive queue unavailable");
            assertThat(f.scalar("SELECT COUNT(*) FROM @sales_order")).isEqualTo(3);
            assertThat(f.scalar("SELECT COUNT(*) FROM @pi_order_link")).isEqualTo(2);
            assertThat(f.scalar("SELECT current_pi_id FROM @pi_series")).isEqualTo(original.getId());
            assertThat(f.lockMapper.selectById(lock.getId()).getConvertedQty()).isEqualTo(1);
            assertThat(f.inventory.snapshot(12,0).committedQty()).isEqualTo(1);
            assertThat(f.tx(()->f.pis.get(original.getId())).getStatus()).isEqualTo("RELEASED");
            doNothing().when(archives).enqueue(any(),eq("CONFIRMATION"));
            var published=f.tx(()->f.pis.confirm(draft.getId()));
            assertThat(published.getRevisionSources().getFirst().orderId()).isNotEqualTo(separate.getId());
            assertThat(f.orders.selectById(separate.getId()).getStatus()).isEqualTo("DRAFT");
            assertThat(f.scalar("SELECT COUNT(*) FROM @sales_order")).isEqualTo(4);
            assertThat(f.lockMapper.selectById(lock.getId()).getConvertedQty()).isEqualTo(2);
        }
    }
}
