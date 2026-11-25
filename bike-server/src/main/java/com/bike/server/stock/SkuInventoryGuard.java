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
        if (sku != null) {
            // 与车型校验使用相同口径，避免限时订单出库挤占其他订单的同SKU库存。
            boolean timed = "TIMED".equals(mapper.selectReservationHoldMode(orderId));
            var snapshot = timed
                    ? mapper.snapshot(sku, orderId) : mapper.selectFulfillmentSnapshot(sku, orderId);
            if (timed && mapper.countLockSources(orderId, sku) > 0 && snapshot.ownedQty() < 1)
                throw new BusinessException("ORDER_RESERVATION_EXPIRED", "该 SKU 的有效占用不足，请重新占用后出库");
            if (snapshot.availableForOrder() < 1)
                throw new BusinessException("SKU_INVENTORY_LOCK_CONFLICT", "该SKU库存已被其他锁定或订单占用");
        }
    }

    public void requireUnreservedRemoval(long vehicleId) {
        Long sku = mapper.vehicleSku(vehicleId);
        if (sku != null && mapper.selectFulfillmentSnapshot(sku, 0).availableForOrder() < 1)
            throw new BusinessException("SKU_INVENTORY_LOCK_CONFLICT", "撤销入库会使该SKU有效承诺失去库存支撑");
    }

    public void requireReacquire(long orderId) {
        for (var item : mapper.reservationItems(orderId)) {
            if (item.remainingQty() > 0 && mapper.snapshot(item.skuId(), orderId).availableForOrder() < item.remainingQty())
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

    public void allocateShipmentSource(long orderId, long vehicleId, String stockNo) {
        Long sku = mapper.vehicleSku(vehicleId);
        if (sku == null || mapper.countLockSources(orderId, sku) == 0) return;
        // 同 SKU 优先消耗最早到期的有效来源，撤销时恢复到原来使用的来源。
        Long lockId = mapper.selectShipmentSource(orderId, sku);
        if (lockId == null) throw new BusinessException("ORDER_RESERVATION_EXPIRED", "该 SKU 的有效占用不足，请重新占用后出库");
        if (mapper.increaseSourceShipped(orderId, lockId) != 1 || mapper.insertShipmentSource(stockNo, orderId, lockId) != 1)
            throw new BusinessException("ORDER_SKU_QTY_CONFLICT", "订单来源出库数量更新失败");
    }

    public void restoreShipmentSource(long orderId, long stockOutId) {
        Long lockId = mapper.selectStockOutSource(stockOutId);
        if (lockId != null && mapper.decreaseSourceShipped(orderId, lockId) != 1)
            throw new BusinessException("ORDER_RESERVATION_EXPIRED", "本次出库使用的锁定来源已到期，请重新占用后撤销");
    }
}
