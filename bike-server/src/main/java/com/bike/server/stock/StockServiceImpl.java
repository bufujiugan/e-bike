package com.bike.server.stock;

import com.bike.server.common.BusinessException;
import com.bike.server.common.CurrentOperator;
import com.bike.server.common.PageResponse;
import com.bike.server.vehicle.VehicleView;
import com.bike.server.order.OrderMapper;
import com.bike.server.order.SalesOrderView;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.Map;

@Service
public class StockServiceImpl implements StockService {
    private final StockMapper stockMapper;
    private final OrderMapper orderMapper;
    @org.springframework.beans.factory.annotation.Autowired private com.bike.server.production.ProductionGuard productionGuard;
    public StockServiceImpl(StockMapper stockMapper, OrderMapper orderMapper) { this.stockMapper = stockMapper; this.orderMapper = orderMapper; }

    @Override public StockValidationResult validateStockIn(StockValidationRequest request) {
        CurrentOperator.requirePermission("STOCK_IN_OPERATE");
        CurrentOperator.requireWorkstation("STOCK_IN");
        VehicleView vehicle = stockMapper.selectVehicleByQrForUpdate(request.vehicleQrCode().trim());
        if (vehicle == null) return new StockValidationResult(false, null, null, "整车不存在");
        if (productionGuard != null) productionGuard.requireVehicleReleased(vehicle.getId());
        return new StockValidationResult(vehicle.getStatus() != null && vehicle.getStatus() == 1, vehicle.getFrameSn(), vehicle.getStatus(), vehicle.getStatus() != null && vehicle.getStatus() == 1 ? "可办理入库" : "只有组装完成的整车才能入库");
    }

    @Override public StockValidationResult validateStockOut(StockValidationRequest request) {
        CurrentOperator.requirePermission("STOCK_OUT_OPERATE");
        CurrentOperator.requireWorkstation("STOCK_OUT");
        VehicleView vehicle = stockMapper.selectVehicleByQrForUpdate(request.vehicleQrCode().trim());
        if (vehicle == null) return new StockValidationResult(false, null, null, "整车不存在");
        if (productionGuard != null) productionGuard.requireVehicleReleased(vehicle.getId());
        if (vehicle.getStatus() == null || vehicle.getStatus() != 2) {
            return new StockValidationResult(false, vehicle.getFrameSn(), vehicle.getStatus(), "只有已入库整车才能出库");
        }
        if (request.customerId() == null || request.orderId() == null) {
            return new StockValidationResult(false, vehicle.getFrameSn(), vehicle.getStatus(), "出库前必须先选择客户和订单");
        }
        SalesOrderView order = orderMapper.selectById(request.orderId());
        if (order == null) return new StockValidationResult(false, vehicle.getFrameSn(), vehicle.getStatus(), "订单不存在");
        if (!request.customerId().equals(order.getCustomerId())) {
            return new StockValidationResult(false, vehicle.getFrameSn(), vehicle.getStatus(), "客户与订单不匹配");
        }
        if (!"CONFIRMED".equals(order.getStatus()) && !"PARTIAL_SHIPPED".equals(order.getStatus())) {
            return new StockValidationResult(false, vehicle.getFrameSn(), vehicle.getStatus(), "订单当前不允许出库");
        }
        if (order.getOrderQty() - order.getShippedQty() <= 0) {
            return new StockValidationResult(false, vehicle.getFrameSn(), vehicle.getStatus(), "订单剩余数量不足");
        }
        if (!java.util.Objects.equals(order.getModelId(), vehicle.getModelId())) {
            return new StockValidationResult(false, vehicle.getFrameSn(), vehicle.getStatus(), "整车车型与订单车型不匹配");
        }
        return new StockValidationResult(true, vehicle.getFrameSn(), vehicle.getStatus(), "可办理出库");
    }

    @Override @Transactional
    public StockRecordView stockIn(StockInRequest request) {
        if (productionGuard != null) productionGuard.lock();
        CurrentOperator.requirePermission("STOCK_IN_OPERATE");
        CurrentOperator.requireWorkstation("STOCK_IN");
        if (request.warehouseId() <= 0 || request.locationId() <= 0 || stockMapper.countEnabledLocation(request.warehouseId(), request.locationId()) != 1) {
            throw new BusinessException("LOCATION_INVALID", "仓库或库位不存在、已停用或不匹配");
        }
        VehicleView vehicle = requireVehicle(request.vehicleQrCode());
        if (productionGuard != null) productionGuard.requireVehicleReleased(vehicle.getId());
        if (vehicle.getStatus() == null || vehicle.getStatus() == 0) throw new BusinessException("VEHICLE_NOT_ASSEMBLED", "只有组装完成的整车才能入库");
        if (vehicle.getStatus() == 2) throw new BusinessException("VEHICLE_ALREADY_STOCKED_IN", "整车已经入库");
        if (vehicle.getStatus() == 3) throw new BusinessException("VEHICLE_ALREADY_STOCKED_OUT", "已出库车辆不能重复入库");
        if (vehicle.getStatus() != 1) throw new BusinessException("INVALID_STATUS_TRANSITION", "整车当前状态不允许入库");
        String stockNo = "IN" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();
        if (stockMapper.updateVehicleStockIn(vehicle.getId(), request.warehouseId(), request.locationId()) != 1) throw new BusinessException("INVALID_STATUS_TRANSITION", "整车入库状态更新失败");
        stockMapper.insertStockIn(stockNo, vehicle.getId(), request.warehouseId(), request.locationId(), CurrentOperator.id());
        stockMapper.insertOperationLog("STOCK_IN", stockNo, "整车入库", vehicle.getFrameSn(), CurrentOperator.id(), CurrentOperator.username(), "仓库:" + request.warehouseId() + ",库位:" + request.locationId(), CurrentOperator.clientIp());
        return stockMapper.selectStockInByNo(stockNo);
    }

    @Override @Transactional
    public StockRecordView stockOut(StockOutRequest request) {
        if (productionGuard != null) productionGuard.lock();
        CurrentOperator.requirePermission("STOCK_OUT_OPERATE");
        CurrentOperator.requireWorkstation("STOCK_OUT");
        if (new HashSet<>(request.vehicleQrCodes()).size() != request.vehicleQrCodes().size()) throw new BusinessException("INVALID_QR_CODE", "同一整车不能重复扫码");
        String operator = CurrentOperator.username();
        SalesOrderView order = orderMapper.selectForUpdate(request.orderId());
        if (order == null) throw new BusinessException("ORDER_NOT_FOUND", "订单不存在");
        if (!request.customerId().equals(order.getCustomerId())) throw new BusinessException("ORDER_CUSTOMER_MISMATCH", "客户与订单不匹配");
        if (!"CONFIRMED".equals(order.getStatus()) && !"PARTIAL_SHIPPED".equals(order.getStatus())) throw new BusinessException("INVALID_STATUS_TRANSITION", "订单当前不允许出库");
        if (order.getOrderQty() - order.getShippedQty() < request.vehicleQrCodes().size()) throw new BusinessException("ORDER_QTY_NOT_ENOUGH", "订单剩余数量不足");
        Set<Long> vehicleIds = new HashSet<>();
        StockRecordView first = null;
        for (String qr : request.vehicleQrCodes()) {
            VehicleView vehicle = requireVehicle(qr);
            if (productionGuard != null) productionGuard.requireVehicleReleased(vehicle.getId());
            if (vehicle.getStatus() == 3) throw new BusinessException("VEHICLE_ALREADY_STOCKED_OUT", "整车已经出库: " + vehicle.getFrameSn());
            if (vehicle.getStatus() == null || vehicle.getStatus() != 2) throw new BusinessException("VEHICLE_NOT_IN_STOCK", "整车未入库: " + vehicle.getFrameSn());
            if (!vehicleIds.add(vehicle.getId())) throw new BusinessException("INVALID_QR_CODE", "同一整车不能重复扫码");
            if (!java.util.Objects.equals(order.getModelId(), vehicle.getModelId())) throw new BusinessException("ORDER_MODEL_MISMATCH", "整车车型与订单车型不匹配: " + vehicle.getFrameSn());
            String stockNo = "OUT" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();
            if (stockMapper.updateVehicleStockOut(vehicle.getId(), request.customerId(), request.orderId()) != 1) throw new BusinessException("INVALID_STATUS_TRANSITION", "整车出库状态更新失败");
            stockMapper.insertStockOut(stockNo, vehicle.getId(), request.customerId(), request.orderId(), CurrentOperator.id());
            stockMapper.insertOrderVehicle(request.orderId(), vehicle.getId(), stockNo);
            stockMapper.insertOperationLog("STOCK_OUT", stockNo, "整车出库", vehicle.getFrameSn(), CurrentOperator.id(), operator, "订单:" + request.orderId(), CurrentOperator.clientIp());
            if (first == null) first = stockMapper.selectStockOutByNo(stockNo);
        }
        if (orderMapper.increaseShipped(request.orderId(), request.vehicleQrCodes().size()) != 1) throw new BusinessException("ORDER_QTY_NOT_ENOUGH", "订单出库数量更新失败");
        orderMapper.updateStatus(request.orderId(), order.getShippedQty() + request.vehicleQrCodes().size() >= order.getOrderQty() ? "SHIPPED" : "PARTIAL_SHIPPED");
        return first;
    }

    @Override @Transactional
    public StockRecordView cancelStockIn(long id, String reason) {
        CurrentOperator.requirePermission("STOCK_IN_OPERATE");
        CurrentOperator.requireWorkstation("STOCK_IN");
        StockRecordView record = stockMapper.selectStockIn(id);
        if (record == null) throw new BusinessException("STOCK_IN_NOT_FOUND", "入库记录不存在");
        if (!"COMPLETED".equals(record.getStatus())) throw new BusinessException("INVALID_STATUS_TRANSITION", "入库记录当前不可撤销");
        if (stockMapper.updateVehicleStockInCancel(record.getVehicleId()) != 1 || stockMapper.cancelStockIn(id, reason, CurrentOperator.id()) != 1) throw new BusinessException("INVALID_STATUS_TRANSITION", "撤销入库失败");
        stockMapper.insertOperationLog("STOCK_IN_CANCEL", String.valueOf(id), "撤销入库", record.getFrameSn(), CurrentOperator.id(), CurrentOperator.username(), reason, CurrentOperator.clientIp());
        return stockMapper.selectStockIn(id);
    }

    @Override @Transactional
    public StockRecordView cancelStockOut(long id, String reason) {
        CurrentOperator.requirePermission("STOCK_OUT_OPERATE");
        CurrentOperator.requireWorkstation("STOCK_OUT");
        StockRecordView record = stockMapper.selectStockOut(id);
        if (record == null) throw new BusinessException("STOCK_OUT_NOT_FOUND", "出库记录不存在");
        if (!"COMPLETED".equals(record.getStatus())) throw new BusinessException("INVALID_STATUS_TRANSITION", "出库记录当前不可撤销");
        if (stockMapper.updateVehicleStockOutCancel(record.getVehicleId(), record.getCustomerId(), record.getOrderNo(), id) != 1 || stockMapper.cancelStockOut(id, reason, CurrentOperator.id()) != 1) throw new BusinessException("INVALID_STATUS_TRANSITION", "撤销出库失败");
        if (orderMapper.decreaseShipped(record.getOrderId(), 1) != 1) {
            throw new BusinessException("ORDER_UPDATE_FAILED", "订单已出库数量回退失败");
        }
        if (stockMapper.updateOrderStatusAfterCancel(record.getOrderId(), "CONFIRMED") != 1) throw new BusinessException("ORDER_UPDATE_FAILED", "订单状态恢复失败");
        stockMapper.insertOperationLog("STOCK_OUT_CANCEL", String.valueOf(id), "撤销出库", record.getFrameSn(), CurrentOperator.id(), CurrentOperator.username(), reason, CurrentOperator.clientIp());
        return stockMapper.selectStockOut(id);
    }

    @Override public PageResponse<StockRecordView> list(String type, long page, long pageSize) { CurrentOperator.requireAnyPermission("STOCK_READ", "INVENTORY_READ"); CurrentOperator.requireWebAccess(); long p=Math.max(1,page), s=Math.min(100,Math.max(1,pageSize)); return new PageResponse<>(stockMapper.selectStockRecords(type,(p-1)*s,s),p,s,stockMapper.countStockRecords(type)); }
    @Override public PageResponse<InventoryRow> inventory(Long modelId, Long warehouseId, long page, long pageSize) { CurrentOperator.requireAnyPermission("INVENTORY_READ", "AGENT_INVENTORY_READ"); Long scopedAgent = null; if (CurrentOperator.hasRole("WEB_AGENT")) { scopedAgent = CurrentOperator.agentId(); if (modelId == null || scopedAgent == null || stockMapper.countAuthorizedModel(scopedAgent, modelId) == 0) throw new BusinessException("FORBIDDEN", "无权查看该车型库存"); } long p=Math.max(1,page),s=Math.min(100,Math.max(1,pageSize)); var rows=stockMapper.selectInventory(modelId,warehouseId,scopedAgent); long from=Math.min(rows.size(),(p-1)*s),to=Math.min(rows.size(),from+s); return new PageResponse<>(rows.subList((int)from,(int)to),p,s,rows.size()); }
    @Override public Map<String, Long> inventorySummary() { CurrentOperator.requirePermission("INVENTORY_READ"); CurrentOperator.requireWebAccess(); long physical = stockMapper.countPhysicalInventory(); long locked = stockMapper.countLockedInventory(); return Map.of("physicalQty", physical, "lockedQty", locked, "availableQty", Math.max(0, physical - locked)); }

    private VehicleView requireVehicle(String qr) { if (qr == null || qr.isBlank()) throw new BusinessException("INVALID_QR_CODE", "整车二维码不能为空"); VehicleView v=stockMapper.selectVehicleByQrForUpdate(qr.trim()); if (v==null) throw new BusinessException("VEHICLE_NOT_FOUND", "整车不存在"); return v; }
}
