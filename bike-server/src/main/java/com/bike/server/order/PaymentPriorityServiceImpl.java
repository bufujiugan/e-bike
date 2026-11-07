package com.bike.server.order;

import com.bike.server.common.BusinessException;
import com.bike.server.common.CurrentOperator;
import com.bike.server.common.OperationLogMapper;
import com.bike.server.lock.InventoryLockMapper;
import com.bike.server.pi.*;
import com.bike.server.production.ProductionGuard;
import com.bike.server.stock.SkuInventoryMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PaymentPriorityServiceImpl implements PaymentPriorityService {
    private static final Logger log = LoggerFactory.getLogger(PaymentPriorityServiceImpl.class);
    private final PaymentPriorityMapper mapper;
    private final PiMapper pis;
    private final PiSeriesMapper series;
    private final PiBusinessMapper warehouses;
    private final OrderMapper orders;
    private final OrderReservationMapper reservations;
    private final SkuInventoryMapper inventory;
    private final InventoryLockMapper locks;
    private final OperationLogMapper logs;
    private final ObjectMapper json;
    private final ProductionGuard guard;

    public PaymentPriorityServiceImpl(PaymentPriorityMapper mapper, PiMapper pis, PiSeriesMapper series,
            PiBusinessMapper warehouses, OrderMapper orders, OrderReservationMapper reservations,
            SkuInventoryMapper inventory, InventoryLockMapper locks, OperationLogMapper logs,
            ObjectMapper json, ProductionGuard guard) {
        this.mapper=mapper; this.pis=pis; this.series=series; this.warehouses=warehouses;
        this.orders=orders; this.reservations=reservations; this.inventory=inventory;
        this.locks=locks; this.logs=logs; this.json=json; this.guard=guard;
    }

    private record Demand(long skuId, long modelId, Long warehouseId, long quantity) {}
    private record CapacityKey(String scope, long id, Long warehouseId) {}

    @Override
    @Transactional(propagation=Propagation.MANDATORY)
    public void cancelUnfulfillableUnpaidOrders(PiView paidPi) {
        guard.lock();
        var modelIds=pis.selectItems(paidPi.getId()).stream().map(PiItemView::getProductModelId).distinct().sorted().toList();
        if(modelIds.isEmpty())return;
        var capacity=new HashMap<CapacityKey,Long>();
        String reason="自动取消：其他客户已付款优先占库，剩余库存不足以满足整单";
        for(var candidate:mapper.selectUnpaidCandidates(modelIds,paidPi.getId())) {
            var demand=demands(candidate);
            if(demand.isEmpty())continue;
            var needed=new LinkedHashMap<CapacityKey,Long>();
            for(var line:demand) {
                needed.merge(new CapacityKey("SKU",line.skuId(),null),line.quantity(),Long::sum);
                needed.merge(new CapacityKey("MODEL",line.modelId(),null),line.quantity(),Long::sum);
                if(line.warehouseId()!=null)needed.merge(new CapacityKey("WAREHOUSE",line.skuId(),line.warehouseId()),line.quantity(),Long::sum);
            }
            // 同一PI的所有SKU先整体校验，再分配余额；不足时不占用部分库存。
            boolean fits=needed.entrySet().stream().allMatch(entry -> capacity.computeIfAbsent(entry.getKey(),this::available)>=entry.getValue());
            if(fits)needed.forEach((key,quantity)->capacity.put(key,capacity.get(key)-quantity));
            else cancel(candidate,paidPi.getId(),reason);
        }
    }

    private long available(CapacityKey key) {
        return switch(key.scope()) {
            case "SKU" -> inventory.selectFulfillmentSnapshot(key.id(),0).availableForOrder();
            case "MODEL" -> reservations.selectFulfillmentSnapshot(key.id(),0).availableForOrder();
            case "WAREHOUSE" -> warehouses.countWarehouseStock(key.warehouseId(),key.id())-warehouses.countWarehouseReserved(key.warehouseId(),key.id(),0);
            default -> throw new IllegalStateException("未知库存维度");
        };
    }

    private List<Demand> demands(UnpaidInventoryCandidate candidate) {
        return switch(candidate.kind()) {
            case "PI" -> {
                PiDocumentSnapshot document;
                try {
                    String snapshot=warehouses.selectDocument(candidate.id());
                    document=snapshot==null?null:json.readValue(snapshot,PiDocumentSnapshot.class);
                } catch(JsonProcessingException error) {
                    throw new IllegalStateException("自动取消核对PI仓库资料失败，事务回滚",error);
                }
                Long warehouseId=document==null||document.business()==null?null:document.business().warehouseId();
                yield pis.selectItems(candidate.id()).stream().map(item->new Demand(item.getSkuId(),item.getProductModelId(),warehouseId,item.getQuantity())).toList();
            }
            case "ORDER" -> {
                var order=orders.selectForUpdate(candidate.id());
                yield inventory.reservationItems(candidate.id()).stream().map(item->new Demand(item.skuId(),order.getModelId(),null,item.remainingQty())).toList();
            }
            case "LOCK" -> {
                var lock=locks.selectById(candidate.id());
                yield List.of(new Demand(lock.getSkuId(),lock.getProductModelId(),null,lock.getLockQty()-lock.getReleasedQty()-lock.getConvertedQty()));
            }
            default -> throw new IllegalStateException("未知未付款需求类型");
        };
    }

    private void cancel(UnpaidInventoryCandidate candidate,long paidPiId,String reason) {
        switch(candidate.kind()) {
            case "PI" -> {
                for(long orderId:series.orderIds(candidate.id()))cancelOrder(orderId,paidPiId,reason);
                for(long draftId:mapper.selectDraftRevisions(candidate.id()))cancelPi(draftId,paidPiId,reason);
                cancelPi(candidate.id(),paidPiId,reason);
            }
            case "ORDER" -> cancelOrder(candidate.id(),paidPiId,reason);
            case "LOCK" -> {
                if(mapper.updateReleasedLock(candidate.id(),reason)!=1)fail();
                audit("INVENTORY_RELEASE",locks.selectById(candidate.id()).getLockNo(),paidPiId,reason);
            }
            default -> throw new IllegalStateException("未知自动取消类型");
        }
        log.info("付款优先占库处理完成 paidPiId={}, cancelledKind={}, cancelledId={}",paidPiId,candidate.kind(),candidate.id());
    }

    private void cancelOrder(long orderId,long paidPiId,String reason) {
        var order=orders.selectForUpdate(orderId);
        var reservation=reservations.selectForUpdate(orderId);
        if(order.getShippedQty()>0||reservation!=null&&java.util.Set.of("PAID","CREDIT").contains(reservation.holdMode()))fail();
        if(reservation!=null&&!"RELEASED".equals(reservation.holdMode())&&reservations.release(orderId)!=1)fail();
        inventory.closeRemainder(orderId);
        if(mapper.updateCancelledOrder(orderId,reason)!=1)fail();
        audit("ORDER_UPDATE",order.getOrderNo(),paidPiId,reason);
    }

    private void cancelPi(long piId,long paidPiId,String reason) {
        if(mapper.updateCancelledPi(piId,reason)!=1)fail();
        audit("PI",Long.toString(piId),paidPiId,reason);
    }

    private void audit(String businessType,String businessId,long paidPiId,String reason) {
        try {
            if(logs.insert(businessType,businessId,"付款优先占库自动取消",CurrentOperator.id(),CurrentOperator.username(),null,
                    json.writeValueAsString(Map.of("triggerPiId",paidPiId,"reason",reason)),CurrentOperator.clientIp(),reason)!=1)fail();
        } catch(JsonProcessingException error) {
            throw new IllegalStateException("自动取消审计序列化失败，事务回滚",error);
        }
    }

    private static void fail() { throw new BusinessException("PAYMENT_PRIORITY_CONFLICT","库存竞争处理失败，请刷新后重试；本次变更未保存"); }
}
