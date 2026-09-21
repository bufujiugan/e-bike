package com.bike.server.order;

import com.bike.server.common.BusinessException;
import org.springframework.stereotype.Component;

@Component
public class OrderInventoryGuard {
    private final OrderReservationMapper mapper;
    @org.springframework.beans.factory.annotation.Autowired private com.bike.server.pi.PiShipmentGuard piShipmentGuard;
    @org.springframework.beans.factory.annotation.Autowired private com.bike.server.stock.SkuInventoryGuard skuInventoryGuard;
    public OrderInventoryGuard(OrderReservationMapper mapper){this.mapper=mapper;}
    public void requireShipment(SalesOrderView order,int quantity){
        var snapshot=mapper.snapshot(order.getModelId(),order.getId());
        if(snapshot.getReservationCount()>0 && snapshot.getOwnedQty()<quantity)
            throw new BusinessException("ORDER_RESERVATION_EXPIRED","订单占用已到期或不足，须重新办理库存占用后出库");
        if(snapshot.availableForOrder()<quantity)
            throw new BusinessException("INVENTORY_LOCK_CONFLICT","库存已被其他锁定或订单占用，不能出库");
    }
    public void requireUnreservedRemoval(long vehicleId){
        Long modelId=mapper.vehicleModel(vehicleId);
        if(modelId==null)throw new BusinessException("VEHICLE_NOT_FOUND","车辆不存在");
        if(mapper.snapshot(modelId,0).availableForOrder()<1)
            throw new BusinessException("INVENTORY_LOCK_CONFLICT","撤销入库会使有效锁定或订单占用失去库存支撑");
        if(skuInventoryGuard!=null)skuInventoryGuard.requireUnreservedRemoval(vehicleId);
    }
    public void requireVehicle(SalesOrderView order,long vehicleId){if(skuInventoryGuard!=null)skuInventoryGuard.requireVehicle(order.getId(),vehicleId);}
    public void recordShipment(long orderId,long vehicleId){if(skuInventoryGuard!=null)skuInventoryGuard.shipped(orderId,vehicleId);}
    public void restoreShipment(long orderId,long vehicleId){if(skuInventoryGuard!=null)skuInventoryGuard.restore(orderId,vehicleId);}
    public void requireEditable(long orderId){
        if (piShipmentGuard != null) piShipmentGuard.requireOrderEditable(orderId);
        if(mapper.countForOrder(orderId)>0)throw new BusinessException("ORDER_RESERVED","锁定转入订单不可直接修改车型或数量，请取消后重新办理");
    }
    public void requireConfirmable(long orderId){
        if(mapper.expiredForOrder(orderId)>0)throw new BusinessException("ORDER_RESERVATION_EXPIRED","订单占用已到期或释放，请重新核对库存占用");
    }
    public void requireRestore(long orderId){
        if(mapper.expiredForOrder(orderId)>0)throw new BusinessException("ORDER_RESERVATION_EXPIRED","来源占用已到期或释放，须重新办理占用后撤销出库");
    }
}
