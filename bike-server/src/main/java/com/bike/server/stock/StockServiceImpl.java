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
    @org.springframework.beans.factory.annotation.Autowired private com.bike.server.common.CustomerScopeAccess customerScope;
    private final StockMapper stockMapper;
    private final OrderMapper orderMapper;
    @org.springframework.beans.factory.annotation.Autowired private com.bike.server.production.ProductionGuard productionGuard;
    @org.springframework.beans.factory.annotation.Autowired private com.bike.server.order.OrderInventoryGuard inventoryGuard;
    @org.springframework.beans.factory.annotation.Autowired private com.bike.server.pi.PiShipmentGuard piShipmentGuard;
    @org.springframework.beans.factory.annotation.Autowired private com.bike.server.pi.PiDispatchRecorder dispatchRecorder;
    @org.springframework.beans.factory.annotation.Autowired private com.bike.server.pi.PiLogisticsMapper logistics;
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
        customerScope.requireCustomer(order.getCustomerId());
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
        if(inventoryGuard!=null)inventoryGuard.requireShipment(order,1);
        if(inventoryGuard!=null)inventoryGuard.requireVehicle(order,vehicle.getId());
        if (piShipmentGuard != null) {
            var pi = piShipmentGuard.requireReleased(order);
            piShipmentGuard.requireVehicle(pi, order.getId(), vehicle.getId());
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
        return performStockOut(request,null);
    }

    @Override @Transactional
    public StockRecordView stockOutForPiBatch(StockOutRequest request,long batchId) {
        return performStockOut(request,batchId);
    }

    private StockRecordView performStockOut(StockOutRequest request,Long batchId) {
        if (productionGuard != null) productionGuard.lock();
        CurrentOperator.requirePermission("STOCK_OUT_OPERATE");
        CurrentOperator.requireWorkstation("STOCK_OUT");
        if (new HashSet<>(request.vehicleQrCodes()).size() != request.vehicleQrCodes().size()) throw new BusinessException("INVALID_QR_CODE", "同一整车不能重复扫码");
        String operator = CurrentOperator.username();
        SalesOrderView order = orderMapper.selectForUpdate(request.orderId());
        if (order == null) throw new BusinessException("ORDER_NOT_FOUND", "订单不存在");
        customerScope.requireCustomer(order.getCustomerId());
        if (!request.customerId().equals(order.getCustomerId())) throw new BusinessException("ORDER_CUSTOMER_MISMATCH", "客户与订单不匹配");
        if (!"CONFIRMED".equals(order.getStatus()) && !"PARTIAL_SHIPPED".equals(order.getStatus())) throw new BusinessException("INVALID_STATUS_TRANSITION", "订单当前不允许出库");
        if (order.getOrderQty() - order.getShippedQty() < request.vehicleQrCodes().size()) throw new BusinessException("ORDER_QTY_NOT_ENOUGH", "订单剩余数量不足");
        if(inventoryGuard!=null)inventoryGuard.requireShipment(order,request.vehicleQrCodes().size());
        var pi = piShipmentGuard == null ? null : piShipmentGuard.requireReleased(order);
        if(batchId!=null) {
            if(pi==null||dispatchRecorder==null)throw new BusinessException("PI_BATCH_MISMATCH","出库批次必须关联有效PI");
            dispatchRecorder.requireBatch(batchId,pi.id());
        } else if(pi!=null&&dispatchRecorder!=null) {
            batchId=dispatchRecorder.begin(pi.id(),UUID.randomUUID().toString(),null,"ORDER_COMMAND");
        }
        Set<Long> vehicleIds = new HashSet<>();
        StockRecordView first = null;
        for (String qr : request.vehicleQrCodes()) {
            VehicleView vehicle = requireVehicle(qr);
            if (productionGuard != null) productionGuard.requireVehicleReleased(vehicle.getId());
            if (vehicle.getStatus() == 3) throw new BusinessException("VEHICLE_ALREADY_STOCKED_OUT", "整车已经出库: " + vehicle.getFrameSn());
            if (vehicle.getStatus() == null || vehicle.getStatus() != 2) throw new BusinessException("VEHICLE_NOT_IN_STOCK", "整车未入库: " + vehicle.getFrameSn());
            if (!vehicleIds.add(vehicle.getId())) throw new BusinessException("INVALID_QR_CODE", "同一整车不能重复扫码");
            if (!java.util.Objects.equals(order.getModelId(), vehicle.getModelId())) throw new BusinessException("ORDER_MODEL_MISMATCH", "整车车型与订单车型不匹配: " + vehicle.getFrameSn());
            if (piShipmentGuard != null) piShipmentGuard.requireVehicle(pi, order.getId(), vehicle.getId());
            if(inventoryGuard!=null)inventoryGuard.requireVehicle(order,vehicle.getId());
            String stockNo = "OUT" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();
            if (stockMapper.updateVehicleStockOut(vehicle.getId(), request.customerId(), request.orderId()) != 1) throw new BusinessException("INVALID_STATUS_TRANSITION", "整车出库状态更新失败");
            stockMapper.insertStockOut(stockNo, vehicle.getId(), request.customerId(), request.orderId(), CurrentOperator.id());
            if(batchId!=null)dispatchRecorder.attach(batchId,stockNo);
            stockMapper.insertOrderVehicle(request.orderId(), vehicle.getId(), stockNo);
            if(inventoryGuard!=null)inventoryGuard.recordShipment(request.orderId(),vehicle.getId());
            stockMapper.insertOperationLog("STOCK_OUT", stockNo, "整车出库", vehicle.getFrameSn(), CurrentOperator.id(), operator, "订单:" + request.orderId(), CurrentOperator.clientIp());
            if (first == null) first = stockMapper.selectStockOutByNo(stockNo);
        }
        if (orderMapper.increaseShipped(request.orderId(), request.vehicleQrCodes().size()) != 1) throw new BusinessException("ORDER_QTY_NOT_ENOUGH", "订单出库数量更新失败");
        orderMapper.updateStatus(request.orderId(), order.getShippedQty() + request.vehicleQrCodes().size() >= order.getOrderQty() ? "SHIPPED" : "PARTIAL_SHIPPED");
        return first;
    }

    @Override @Transactional
    public StockRecordView cancelStockIn(long id, String reason) {
        if(productionGuard!=null)productionGuard.lock();
        CurrentOperator.requirePermission("STOCK_IN_OPERATE");
        CurrentOperator.requireWorkstation("STOCK_IN");
        StockRecordView record = stockMapper.selectStockIn(id);
        if (record == null) throw new BusinessException("STOCK_IN_NOT_FOUND", "入库记录不存在");
        if (!"COMPLETED".equals(record.getStatus())) throw new BusinessException("INVALID_STATUS_TRANSITION", "入库记录当前不可撤销");
        if(inventoryGuard!=null){
            if(productionGuard!=null)productionGuard.requireVehicleReleased(record.getVehicleId());
            inventoryGuard.requireUnreservedRemoval(record.getVehicleId());
        }
        if (stockMapper.updateVehicleStockInCancel(record.getVehicleId()) != 1 || stockMapper.cancelStockIn(id, reason, CurrentOperator.id()) != 1) throw new BusinessException("INVALID_STATUS_TRANSITION", "撤销入库失败");
        stockMapper.insertOperationLog("STOCK_IN_CANCEL", String.valueOf(id), "撤销入库", record.getFrameSn(), CurrentOperator.id(), CurrentOperator.username(), reason, CurrentOperator.clientIp());
        return stockMapper.selectStockIn(id);
    }

    @Override @Transactional
    public StockRecordView cancelStockOut(long id, String reason) {
        if(productionGuard!=null)productionGuard.lock();
        CurrentOperator.requirePermission("STOCK_OUT_OPERATE");
        CurrentOperator.requireWorkstation("STOCK_OUT");
        StockRecordView record = stockMapper.selectStockOut(id);
        if(record!=null)customerScope.requireCustomer(record.getCustomerId());
        if (record == null) throw new BusinessException("STOCK_OUT_NOT_FOUND", "出库记录不存在");
        if (!"COMPLETED".equals(record.getStatus())) throw new BusinessException("INVALID_STATUS_TRANSITION", "出库记录当前不可撤销");
        if(logistics!=null&&logistics.shippedForStock(id)>0)
            throw new BusinessException("LOGISTICS_SHIPPED","此出库已确认物流发运，须授权人员先撤回物流并核对实际货物");
        SalesOrderView restoreOrder=orderMapper.selectForUpdate(record.getOrderId());
        if(restoreOrder!=null && java.util.Set.of("CANCELLED","CLOSED").contains(restoreOrder.getStatus()))
            throw new BusinessException("INVALID_STATUS_TRANSITION","订单已关闭，不能撤销出库重新打开订单");
        if(inventoryGuard!=null)inventoryGuard.requireRestore(record.getOrderId());
        if (stockMapper.updateVehicleStockOutCancel(record.getVehicleId(), record.getCustomerId(), record.getOrderNo(), id) != 1 || stockMapper.cancelStockOut(id, reason, CurrentOperator.id()) != 1) throw new BusinessException("INVALID_STATUS_TRANSITION", "撤销出库失败");
        if (orderMapper.decreaseShipped(record.getOrderId(), 1) != 1) {
            throw new BusinessException("ORDER_UPDATE_FAILED", "订单已出库数量回退失败");
        }
        if(inventoryGuard!=null)inventoryGuard.restoreShipment(record.getOrderId(),record.getVehicleId());
        if (stockMapper.updateOrderStatusAfterCancel(record.getOrderId(), "CONFIRMED") != 1) throw new BusinessException("ORDER_UPDATE_FAILED", "订单状态恢复失败");
        stockMapper.insertOperationLog("STOCK_OUT_CANCEL", String.valueOf(id), "撤销出库", record.getFrameSn(), CurrentOperator.id(), CurrentOperator.username(), reason, CurrentOperator.clientIp());
        return stockMapper.selectStockOut(id);
    }

    @Override public StockRecordView getByNo(String type, String stockNo) {
        CurrentOperator.requireAnyPermission("STOCK_READ", "INVENTORY_READ");
        CurrentOperator.requireInternalWebAccess();
        if (CurrentOperator.hasRole("WEB_AGENT")) throw new BusinessException("FORBIDDEN", "代理商不能查看仓库记录");
        if (stockNo == null || stockNo.isBlank()) throw new BusinessException("INVALID_PARAMETER", "业务单号不能为空");
        StockRecordView record = "IN".equals(type) ? stockMapper.selectStockInByNo(stockNo.trim()) : stockMapper.selectStockOutByNo(stockNo.trim());
        if(record!=null && !"IN".equals(type))customerScope.requireCustomer(record.getCustomerId());
        if (record == null) throw new BusinessException("STOCK_RECORD_NOT_FOUND", "出入库记录不存在");
        return record;
    }
    @Override public PageResponse<StockRecordView> list(String type, long page, long pageSize) { CurrentOperator.requireAnyPermission("STOCK_READ", "INVENTORY_READ"); CurrentOperator.requireInternalWebAccess(); long p=Math.max(1,page), s=Math.min(100,Math.max(1,pageSize)); return new PageResponse<>(stockMapper.selectStockRecords(type,(p-1)*s,s),p,s,stockMapper.countStockRecords(type)); }
    @Override @Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ) public PageResponse<InventoryRow> inventory(Long modelId, Long warehouseId, long page, long pageSize) { CurrentOperator.requireAnyPermission("INVENTORY_READ", "AGENT_INVENTORY_READ"); Long scopedAgent = null; if (CurrentOperator.hasRole("WEB_AGENT")) { scopedAgent = CurrentOperator.agentId(); if (scopedAgent == null || (modelId != null && stockMapper.countAuthorizedModel(scopedAgent, modelId) == 0)) throw new BusinessException("FORBIDDEN", "无权查看该车型库存"); } long p=Math.min(10000000,Math.max(1,page)),s=Math.min(100,Math.max(1,pageSize)); return new PageResponse<>(stockMapper.selectInventory(modelId,warehouseId,scopedAgent,(p-1)*s,s),p,s,stockMapper.countInventory(modelId,warehouseId,scopedAgent)); }
    @Override public Map<String, Long> inventorySummary() { CurrentOperator.requirePermission("INVENTORY_READ"); CurrentOperator.requireInternalWebAccess(); var result=new java.util.LinkedHashMap<String,Long>(); stockMapper.selectInventorySummary().forEach((key,value)->result.put(key,((Number)value).longValue())); return result; }

    private VehicleView requireVehicle(String qr) { if (qr == null || qr.isBlank()) throw new BusinessException("INVALID_QR_CODE", "整车二维码不能为空"); VehicleView v=stockMapper.selectVehicleByQrForUpdate(qr.trim()); if (v==null) throw new BusinessException("VEHICLE_NOT_FOUND", "整车不存在"); return v; }
}
