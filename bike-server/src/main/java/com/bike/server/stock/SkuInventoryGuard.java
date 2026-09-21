package com.bike.server.stock;

import com.bike.server.common.BusinessException;
import org.springframework.stereotype.Component;

@Component
public class SkuInventoryGuard {
    private final SkuInventoryMapper mapper;
    public SkuInventoryGuard(SkuInventoryMapper mapper) { this.mapper = mapper; }

    public void requireVehicle(long orderId, long vehicleId) {
        Long sku = mapper.vehicleSku(vehicleId);
        var items = mapper.reservationItems(orderId);
        if (!items.isEmpty() && (sku == null || items.stream().noneMatch(i -> i.skuId() == sku && i.remainingQty() > 0)))
            throw new BusinessException("ORDER_SKU_MISMATCH", "整车SKU与订单固定SKU不匹配或该SKU已发完");
        if (sku != null && mapper.snapshot(sku, orderId).availableForOrder() < 1)
            throw new BusinessException("SKU_INVENTORY_LOCK_CONFLICT", "该SKU库存已被其他锁定或订单占用");
    }

    public void requireUnreservedRemoval(long vehicleId) {
        Long sku = mapper.vehicleSku(vehicleId);
        if (sku != null && mapper.snapshot(sku, 0).availableForOrder() < 1)
            throw new BusinessException("SKU_INVENTORY_LOCK_CONFLICT", "撤销入库会使该SKU有效承诺失去库存支撑");
    }

    public void requireReacquire(long orderId) {
        for (var item : mapper.reservationItems(orderId)) {
            if (mapper.snapshot(item.skuId(), orderId).availableForOrder() < item.remainingQty())
                throw new BusinessException("SKU_INVENTORY_NOT_ENOUGH", "该订单SKU库存不足，不能重新占用；已登记收款保持不变");
        }
    }

    public void shipped(long orderId, long vehicleId) {
        var items = mapper.reservationItems(orderId);
        if (items.isEmpty()) return;
        Long sku = mapper.vehicleSku(vehicleId);
        if (sku == null || mapper.increaseShipped(orderId, sku) != 1)
            throw new BusinessException("ORDER_SKU_QTY_CONFLICT", "订单SKU出库数量更新失败");
    }

    public void restore(long orderId, long vehicleId) {
        if (mapper.reservationItems(orderId).isEmpty()) return;
        Long sku = mapper.vehicleSku(vehicleId);
        if (sku == null || mapper.decreaseShipped(orderId, sku) != 1)
            throw new BusinessException("ORDER_SKU_QTY_CONFLICT", "订单SKU出库数量回退失败");
    }
}
