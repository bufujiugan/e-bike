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
class PiQuantityRevisionMysqlTest {
    @Test void shipmentRacingReductionCannotExceedEitherPublishedQuantity()throws Exception {
        try(var f=new SkuCommitmentMysqlTest.Fixture(true);var pool=Executors.newFixedThreadPool(2)) {
            var original=PiPaymentCorrectionMysqlTest.paid(f);f.addVehicle(103,10);
            var increased=revise(f,original,"increase-race",2);f.tx(()->f.pis.confirm(increased.getId()));
            var released=f.tx(()->f.pis.release(increased.getId(),new PiReleaseRequest("核对两台")));
            var reduced=revise(f,released,"reduce-race",1);
            long order=released.getFulfillment().stream().filter(l->l.skuId()==10).findFirst().orElseThrow().orderId();
            var start=new CountDownLatch(1);
            Callable<Boolean> confirm=()->{start.await();try{f.tx(()->f.pis.confirm(reduced.getId()));return true;}catch(BusinessException e){assertThat(e.getCode()).isEqualTo("PI_QUANTITY_BELOW_SHIPPED");return false;}};
            Callable<Boolean> ship=()->{start.await();try{f.tx(()->f.stock.stockOut(new StockOutRequest(1L,order,List.of("QR100","QR103"))));return true;}catch(BusinessException e){assertThat(e.getCode()).isIn("PI_SHIPMENT_NOT_RELEASED","ORDER_QTY_NOT_ENOUGH");return false;}};
            var a=pool.submit(confirm);var b=pool.submit(ship);start.countDown();
            assertThat(a.get(15,TimeUnit.SECONDS)^b.get(15,TimeUnit.SECONDS)).isTrue();
            var item=f.inventory.reservationItems(order).getFirst();
            assertThat(item.shippedQty()).isLessThanOrEqualTo(item.orderedQty());
            assertThat(f.scalar("SELECT COUNT(*) FROM @stock_out_record WHERE status='COMPLETED'")).isEqualTo(item.shippedQty());
            assertThat(f.inventory.snapshot(10,0).availableForOrder()).isGreaterThanOrEqualTo(0);
        }
    }
    static PiCreateRequest edit(PiView pi,int redQuantity) {
        var p=PiRevisionLifecycleMysqlTest.edit(pi,"CNY",1);
        return new PiCreateRequest(p.customerId(),p.agentId(),p.sourceOrderId(),p.tradeType(),p.currencyCode(),p.plannedShipDate(),
                p.discountAmount(),p.taxAmount(),p.freightAmount(),BigDecimal.ZERO,BigDecimal.ZERO,p.remark(),p.document(),
                p.items().stream().map(i->new PiItemRequest(i.skuId(),i.skuId()==10?redQuantity:i.quantity(),i.unitPrice(),i.discountAmount(),i.taxAmount())).toList());
    }
    static PiView revise(SkuCommitmentMysqlTest.Fixture f,PiView original,String key,int redQuantity) {
        var draft=f.tx(()->PiRevisionLifecycleMysqlTest.revisions(f).create(original.getId(),new PiRevisionRequest(key,original.getRevision(),"调整未发数量")));
        return f.tx(()->f.pis.update(draft.getId(),edit(draft,redQuantity)));
    }
    @Test void draftHasNoCommitmentEffectAndConfirmationResizesThenShipsExactNewQuantity()throws Exception {
        try(var f=new SkuCommitmentMysqlTest.Fixture(true)) {
            var original=PiPaymentCorrectionMysqlTest.paid(f);f.addVehicle(103,10);
            long order=original.getFulfillment().stream().filter(l->l.skuId()==10).findFirst().orElseThrow().orderId();
            var draft=revise(f,original,"increase",2);
            assertThat(f.inventory.reservationItems(order).getFirst().orderedQty()).isEqualTo(1);
            assertThat(f.inventory.snapshot(10,0).committedQty()).isEqualTo(1);
            f.tx(()->f.pis.confirm(draft.getId()));
            assertThat(f.inventory.reservationItems(order).getFirst().orderedQty()).isEqualTo(2);
            assertThat(f.orders.selectById(order).getOrderQty()).isEqualTo(2);
            assertThat(f.inventory.snapshot(10,0).committedQty()).isEqualTo(2);
            assertThat(f.scalar("SELECT converted_qty FROM @inventory_lock WHERE sku_id=10")).isEqualTo(1);
            f.tx(()->f.pis.release(draft.getId(),new PiReleaseRequest("新数量重新核对")));
            f.tx(()->f.stock.stockOut(new StockOutRequest(1L,order,List.of("QR100","QR103"))));
            assertThat(f.orders.selectById(order).getStatus()).isEqualTo("SHIPPED");
            assertThat(f.tx(()->f.pis.get(original.getId())).getItems().stream().filter(i->i.getSkuId()==10).findFirst().orElseThrow().getQuantity()).isEqualTo(1);
            var next=f.tx(()->PiRevisionLifecycleMysqlTest.revisions(f).create(draft.getId(),new PiRevisionRequest("below-shipped",2,"测试下限")));
            assertThatThrownBy(()->f.tx(()->f.pis.update(next.getId(),edit(next,1)))).hasMessageContaining("实际已出库");
        }
    }
    @Test void reductionReleasesOnlyDifferenceAndAuditFailureRollsBackPublicationAndInventory()throws Exception {
        try(var f=new SkuCommitmentMysqlTest.Fixture(true)) {
            var original=PiPaymentCorrectionMysqlTest.paid(f);f.addVehicle(103,10);
            var increase=revise(f,original,"more",2);var published=f.tx(()->f.pis.confirm(increase.getId()));
            var reduction=revise(f,published,"less",1);
            var quantity=ReflectionTestUtils.getField(f.pis,"quantityRevisions");var logs=ReflectionTestUtils.getField(quantity,"logs");
            ReflectionTestUtils.setField(quantity,"logs",mock(OperationLogMapper.class));
            assertThatThrownBy(()->f.tx(()->f.pis.confirm(reduction.getId()))).hasMessageContaining("审计");
            assertThat(f.inventory.snapshot(10,0).committedQty()).isEqualTo(2);
            assertThat(f.scalar("SELECT current_pi_id FROM @pi_series")).isEqualTo(increase.getId());
            assertThat(f.tx(()->f.pis.get(reduction.getId())).getStatus()).isEqualTo("DRAFT");
            ReflectionTestUtils.setField(quantity,"logs",logs);
            f.tx(()->f.pis.confirm(reduction.getId()));
            assertThat(f.inventory.snapshot(10,0).committedQty()).isEqualTo(1);
            assertThat(f.inventory.snapshot(10,0).availableForOrder()).isEqualTo(1);
            assertThat(f.scalar("SELECT COUNT(*) FROM @pi_payment")).isEqualTo(1);
        }
    }
    @Test void insufficientSkuOrLegacyModelCapacityAndExpiredReservationsCannotPublish()throws Exception {
        try(var f=new SkuCommitmentMysqlTest.Fixture(true)) {
            var original=PiPaymentCorrectionMysqlTest.paid(f);var draft=revise(f,original,"insufficient",2);
            assertThatThrownBy(()->f.tx(()->f.pis.confirm(draft.getId()))).hasMessageContaining("SKU当前可用库存");
            f.addVehicle(103,10);f.legacyLock(3);
            assertThatThrownBy(()->f.tx(()->f.pis.confirm(draft.getId()))).hasMessageContaining("车型锁定");
            f.exec("UPDATE @inventory_lock SET status='RELEASED' WHERE lock_no='LEGACY'");
            f.exec("UPDATE @order_reservation SET hold_mode='TIMED',expires_at=DATE_SUB(NOW(3),INTERVAL 1 DAY)");
            assertThatThrownBy(()->f.tx(()->f.pis.confirm(draft.getId()))).hasMessageContaining("有效未关闭子单");
            assertThat(f.scalar("SELECT current_pi_id FROM @pi_series")).isEqualTo(original.getId());
            assertThat(f.tx(()->f.pis.get(original.getId())).getStatus()).isEqualTo("RELEASED");
        }
    }
    @Test void multipleOrdersForOneSkuApplyTotalDeltaOnceAndZeroChildKeepsOriginalHistory()throws Exception {
        try(var f=new SkuCommitmentMysqlTest.Fixture(true)) {
            var original=PiPaymentCorrectionMysqlTest.paid(f);f.addVehicle(103,10);
            var second=f.skuOrder(1);f.tx(()->f.orders.updateStatus(second.getId(),"CONFIRMED"));
            f.tx(()->f.piSeries.link(original.getId(),second.getId(),"SKU_CONVERSION"));
            var draft=revise(f,original,"combine",1);
            f.tx(()->f.pis.confirm(draft.getId()));
            assertThat(f.inventory.snapshot(10,0).committedQty()).isEqualTo(1);
            assertThat(f.orders.selectById(second.getId()).getStatus()).isEqualTo("CANCELLED");
            assertThat(f.inventory.reservationItems(second.getId()).getFirst().closedQty()).isEqualTo(1);
            assertThat(f.orders.selectById(second.getId()).getOrderQty()).isEqualTo(1);
            assertThat(f.scalar("SELECT COUNT(*) FROM @pi_order_link")).isEqualTo(3);
        }
    }
}
