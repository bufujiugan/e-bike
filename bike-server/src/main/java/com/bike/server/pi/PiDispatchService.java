package com.bike.server.pi;

import com.bike.server.common.*;
import com.bike.server.order.OrderMapper;
import com.bike.server.production.ProductionGuard;
import com.bike.server.stock.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validator;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PiDispatchService {
    @org.springframework.beans.factory.annotation.Autowired private com.bike.server.common.CustomerScopeAccess customerScope;
    private final PiDispatchMapper mapper;
    private final PiSeriesMapper series;
    private final PiDispatchRecorder recorder;
    private final StockService stock;
    private final OrderMapper orders;
    private final ProductionGuard guard;
    private final OperationLogMapper logs;
    private final Validator validator;
    private final ObjectMapper json;
    public PiDispatchService(PiDispatchMapper mapper,PiSeriesMapper series,PiDispatchRecorder recorder,StockService stock,
            OrderMapper orders,ProductionGuard guard,OperationLogMapper logs,Validator validator,ObjectMapper json) {
        this.mapper=mapper;this.series=series;this.recorder=recorder;this.stock=stock;this.orders=orders;
        this.guard=guard;this.logs=logs;this.validator=validator;this.json=json;
    }
    @Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public PageResponse<Map<String,Object>> options(String keyword,long page) {
        requireWarehouse();long p=Math.min(10000000,Math.max(1,page));String query=keyword==null||keyword.isBlank()?null:keyword.trim();
        return new PageResponse<>(mapper.options(query,(p-1)*20),p,20,mapper.countOptions(query));
    }
    public Map<String,Object> warehouse(long piId) {
        requireWarehouse();var header=mapper.warehouseHeader(piId);
        if(header==null)fail("PI_NOT_FOUND","当前PI不存在");
        customerScope.requireCustomer(((Number)header.get("customerId")).longValue());
        if(!"RELEASED".equals(header.get("status")))fail("PI_SHIPMENT_NOT_RELEASED","PI尚未放行出库");
        var result=new LinkedHashMap<>(header);
        // Warehouse staff receive quantities only, never prices, payment facts or hold mode/deadlines.
        result.put("orders",series.fulfillment(piId).stream().map(line->{
            var row=new LinkedHashMap<String,Object>();row.put("orderId",line.orderId());row.put("orderNo",line.orderNo());
            row.put("skuId",line.skuId());row.put("skuCode",line.skuCode());row.put("status",line.status());
            row.put("orderedQty",line.orderedQty());row.put("shippedQty",line.shippedQty());row.put("closedQty",line.closedQty());row.put("remainingQty",line.remainingQty());return row;
        }).toList());
        return result;
    }
    @Transactional
    public PiDispatchBatch dispatch(long piId,PiDispatchRequest input) {
        requireWarehouse();
        if(input==null||!validator.validate(input).isEmpty())fail("INVALID_PARAMETER","出库批次参数不合法");
        var vehicles=input.vehicles().stream().map(v->new PiDispatchRequest.Vehicle(v.orderId(),v.vehicleQrCode().trim()))
                .sorted(Comparator.comparingLong(PiDispatchRequest.Vehicle::orderId).thenComparing(PiDispatchRequest.Vehicle::vehicleQrCode)).toList();
        if(vehicles.stream().map(PiDispatchRequest.Vehicle::vehicleQrCode).distinct().count()!=vehicles.size())fail("INVALID_QR_CODE","同一整车不能重复扫码");
        var request=new PiDispatchRequest(input.requestId().trim(),vehicles);
        guard.lock();
        var header=mapper.warehouseHeader(piId);if(header==null)fail("PI_NOT_FOUND","当前PI不存在");
        customerScope.requireCustomer(((Number)header.get("customerId")).longValue());
        String hash=hash(request);
        var previous=mapper.prior(piId,CurrentOperator.id(),request.requestId());
        if(previous!=null) {
            if(!Objects.equals(hash,previous.payloadHash()))fail("PI_DISPATCH_CONFLICT","同一批次请求标识不能用于不同车辆");
            return mapper.get(previous.id());
        }
        var ids=series.orderIds(piId);
        for(long id:ids)orders.selectForUpdate(id);
        var groups=new TreeMap<Long,List<String>>();
        for(var vehicle:vehicles) {
            if(!ids.contains(vehicle.orderId()))fail("PI_ORDER_MISMATCH","车辆所选订单不属于本PI");
            groups.computeIfAbsent(vehicle.orderId(),ignored->new ArrayList<>()).add(vehicle.vehicleQrCode());
        }
        long batch=recorder.begin(piId,request.requestId(),hash,"PI_COMMAND");
        var record=mapper.get(batch);
        for(var entry:groups.entrySet()) stock.stockOutForPiBatch(new StockOutRequest(record.customerId(),entry.getKey(),entry.getValue()),batch);
        var result=mapper.get(batch);
        if(result.activeQty()!=vehicles.size())fail("PI_BATCH_FAILED","出库批次数量校验失败");
        if(logs.insert("STOCK_OUT_BATCH",result.batchNo(),"PI整批出库",CurrentOperator.id(),CurrentOperator.username(),null,
                "{\"quantity\":"+result.activeQty()+"}",CurrentOperator.clientIp(),"PI:"+piId)!=1)fail("PI_BATCH_FAILED","出库批次审计失败");
        return result;
    }
    public Map<String,Object> warehouseBatch(long id) {
        requireWarehouse();var batch=mapper.get(id);if(batch==null)fail("PI_BATCH_NOT_FOUND","出库批次不存在");
        customerScope.requireCustomer(batch.customerId());
        return Map.of("batch",batch,"vehicles",mapper.vehicles(id));
    }
    private String hash(PiDispatchRequest request) {try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(request)));}catch(Exception e){throw new IllegalStateException(e);}}
    private static void requireWarehouse() {
        CurrentOperator.requirePermission("STOCK_OUT_OPERATE");CurrentOperator.requireWorkstation("STOCK_OUT");
        if(CurrentOperator.hasRole("WEB_AGENT"))fail("FORBIDDEN","代理商不能办理内部出库");
    }
    private static void fail(String code,String message){throw new BusinessException(code,message);}
}
