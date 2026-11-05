package com.bike.server.order;

import com.bike.server.auth.UserAuthRecord;
import com.bike.server.common.*;
import com.bike.server.lock.InventoryLockMapper;
import com.bike.server.pi.*;
import com.bike.server.production.ProductionGuard;
import com.bike.server.stock.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PaymentPriorityServiceTest {
    final PaymentPriorityMapper mapper=mock(PaymentPriorityMapper.class);
    final PiMapper pis=mock(PiMapper.class);
    final PiSeriesMapper series=mock(PiSeriesMapper.class);
    final PiBusinessMapper warehouses=mock(PiBusinessMapper.class);
    final OrderMapper orders=mock(OrderMapper.class);
    final OrderReservationMapper reservations=mock(OrderReservationMapper.class);
    final SkuInventoryMapper inventory=mock(SkuInventoryMapper.class);
    final InventoryLockMapper locks=mock(InventoryLockMapper.class);
    final OperationLogMapper logs=mock(OperationLogMapper.class);
    final ProductionGuard guard=mock(ProductionGuard.class);
    final PaymentPriorityService service=new PaymentPriorityServiceImpl(mapper,pis,series,warehouses,orders,reservations,inventory,locks,logs,new ObjectMapper(),guard);
    PiView paid;

    @BeforeEach void setup() {
        var auth=new UsernamePasswordAuthenticationToken("tester",null,List.of(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN")));
        auth.setDetails(new UserAuthRecord(1,"tester","","WEB_INTERNAL",null,null,true));
        SecurityContextHolder.getContext().setAuthentication(auth);
        paid=new PiView();paid.setId(1L);
        when(pis.selectItems(1L)).thenReturn(List.of(item(10,1)));
        when(logs.insert(any(),any(),any(),any(),any(),any(),any(),any(),any())).thenReturn(1);
        when(mapper.updateCancelledPi(anyLong(),anyString())).thenReturn(1);
        when(mapper.updateCancelledOrder(anyLong(),anyString())).thenReturn(1);
        when(reservations.release(anyLong())).thenReturn(1);
        capacity(10,3);capacity(11,3);
    }
    @AfterEach void cleanup(){SecurityContextHolder.clearContext();}

    @Test void firstUnpaidOrderFitsButLaterOrderIsCancelledWholeWithReason() {
        when(mapper.selectUnpaidCandidates(List.of(4L),1L)).thenReturn(List.of(candidate("ORDER",2),candidate("ORDER",3)));
        order(2,2);order(3,2);
        service.cancelUnfulfillableUnpaidOrders(paid);
        verify(mapper,never()).updateCancelledOrder(eq(2L),anyString());
        verify(mapper).updateCancelledOrder(eq(3L),contains("剩余库存不足以满足整单"));
        verify(reservations).release(3);
        verify(inventory).closeRemainder(3);
        verify(logs).insert(eq("ORDER_UPDATE"),eq("SO3"),any(),any(),any(),any(),contains("triggerPiId"),any(),contains("自动取消"));
    }

    @Test void oneSkuShortageCancelsEveryOrderAndDraftRevisionOfPiWithoutConsumingOtherSku() {
        when(mapper.selectUnpaidCandidates(List.of(4L),1L)).thenReturn(List.of(candidate("PI",2),candidate("PI",3)));
        when(pis.selectItems(2L)).thenReturn(List.of(item(10,4),item(11,2)));
        when(pis.selectItems(3L)).thenReturn(List.of(item(11,3)));
        when(series.orderIds(2L)).thenReturn(List.of(20L,21L));
        when(mapper.selectDraftRevisions(2L)).thenReturn(List.of(22L));
        order(20,4);order(21,2);
        service.cancelUnfulfillableUnpaidOrders(paid);
        verify(mapper).updateCancelledOrder(eq(20L),anyString());
        verify(mapper).updateCancelledOrder(eq(21L),anyString());
        verify(mapper).updateCancelledPi(eq(2L),anyString());
        verify(mapper).updateCancelledPi(eq(22L),anyString());
        verify(mapper,never()).updateCancelledPi(eq(3L),anyString());
    }

    @Test void specifiedWarehouseShortageCancelsEvenWithGlobalStock() {
        when(mapper.selectUnpaidCandidates(List.of(4L),1L)).thenReturn(List.of(candidate("PI",2)));
        when(pis.selectItems(2L)).thenReturn(List.of(item(10,2)));
        when(warehouses.selectDocument(2L)).thenReturn("{\"business\":{\"warehouseId\":7}}");
        when(warehouses.countWarehouseStock(7L,10L)).thenReturn(1);
        service.cancelUnfulfillableUnpaidOrders(paid);
        verify(mapper).updateCancelledPi(eq(2L),anyString());
    }

    @Test void auditFailurePropagatesForTransactionRollback() {
        when(mapper.selectUnpaidCandidates(List.of(4L),1L)).thenReturn(List.of(candidate("ORDER",2)));
        order(2,4);
        when(logs.insert(any(),any(),any(),any(),any(),any(),any(),any(),any())).thenReturn(0);
        assertThatThrownBy(()->service.cancelUnfulfillableUnpaidOrders(paid)).isInstanceOf(BusinessException.class);
    }

    @Test void paidOrShippedOrderCannotBeCancelledEvenIfCandidateIsStale() {
        when(mapper.selectUnpaidCandidates(List.of(4L),1L)).thenReturn(List.of(candidate("ORDER",2)));
        order(2,4);orders.selectForUpdate(2L).setShippedQty(1);
        assertThatThrownBy(()->service.cancelUnfulfillableUnpaidOrders(paid)).isInstanceOf(BusinessException.class);
        verify(mapper,never()).updateCancelledOrder(anyLong(),anyString());
    }

    void capacity(long sku,int count) {
        when(inventory.selectFulfillmentSnapshot(sku,0)).thenReturn(new SkuInventorySnapshot(count,0,0));
        var model=new OrderInventorySnapshot();model.setPhysicalQty(20);
        when(reservations.selectFulfillmentSnapshot(4,0)).thenReturn(model);
    }
    void order(long id,int quantity) {
        var order=new SalesOrderView();order.setId(id);order.setOrderNo("SO"+id);order.setModelId(4L);
        when(orders.selectForUpdate(id)).thenReturn(order);
        when(inventory.reservationItems(id)).thenReturn(List.of(new SkuReservationItem(10,quantity,0,0)));
        when(reservations.selectForUpdate(id)).thenReturn(new OrderReservationView(id,"SO"+id,"CONFIRMED","TIMED","TIMED_ACTIVE",LocalDateTime.now().plusDays(1),quantity,0,null,null,null));
    }
    static PiItemView item(long sku,int quantity){var item=new PiItemView();item.setSkuId(sku);item.setProductModelId(4L);item.setQuantity(quantity);return item;}
    static UnpaidInventoryCandidate candidate(String kind,long id){return new UnpaidInventoryCandidate(kind,id,LocalDateTime.now());}
}
