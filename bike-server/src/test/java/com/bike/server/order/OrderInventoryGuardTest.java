package com.bike.server.order;
import com.bike.server.common.BusinessException;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class OrderInventoryGuardTest {
    final OrderReservationMapper mapper=mock(OrderReservationMapper.class);
    final OrderInventoryGuard guard=new OrderInventoryGuard(mapper);
    SalesOrderView order(){var o=new SalesOrderView();o.setId(1L);o.setModelId(27L);return o;}
    void snapshot(long orderId,long physical,long committed,long owned,long reservationCount){
        var s=new OrderInventorySnapshot();s.setPhysicalQty(physical);s.setCommittedQty(committed);s.setOwnedQty(owned);s.setReservationCount(reservationCount);
        when(mapper.snapshot(27,orderId)).thenReturn(s);
    }
    @Test void otherOrdersCannotConsumeCommittedInventory(){
        snapshot(1,10,8,0,0);
        assertThatCode(()->guard.requireShipment(order(),2)).doesNotThrowAnyException();
        assertThatThrownBy(()->guard.requireShipment(order(),3)).isInstanceOf(BusinessException.class).hasMessageContaining("其他锁定");
    }
    @Test void ownReservationIsNotDeductedTwice(){
        snapshot(1,10,10,8,1);
        assertThatCode(()->guard.requireShipment(order(),8)).doesNotThrowAnyException();
        assertThatThrownBy(()->guard.requireShipment(order(),9)).isInstanceOf(BusinessException.class);
    }
    @Test void expiredReservationCannotShipEvenWithFreeStock(){
        snapshot(1,10,0,0,1);
        assertThatThrownBy(()->guard.requireShipment(order(),1)).isInstanceOf(BusinessException.class).hasMessageContaining("到期");
    }
    @Test void cancellationOfStockInMustPreserveCommitmentBacking(){
        when(mapper.vehicleModel(5)).thenReturn(27L);snapshot(0,3,3,0,0);
        assertThatThrownBy(()->guard.requireUnreservedRemoval(5)).isInstanceOf(BusinessException.class);
    }
    @Test void databaseExpiryDecisionBlocksConfirmAndRestore(){
        when(mapper.expiredForOrder(1)).thenReturn(1);
        assertThatThrownBy(()->guard.requireConfirmable(1)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->guard.requireRestore(1)).isInstanceOf(BusinessException.class);
    }
}
