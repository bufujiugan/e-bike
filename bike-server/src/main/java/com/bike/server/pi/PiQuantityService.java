package com.bike.server.pi;

import com.bike.server.common.*;
import com.bike.server.order.*;
import com.bike.server.stock.SkuInventoryMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Caller holds the inventory mutex and every linked order/reservation row before invoking this service. */
@Service
public class PiQuantityService {
    private final PiSeriesMapper series;
    private final PiQuantityMapper quantities;
    private final SkuInventoryMapper inventory;
    private final OrderReservationMapper reservations;
    private final OrderMapper orders;
    private final OperationLogMapper logs;
    private final ObjectMapper json;
    private final PiMapper pis;

    public PiQuantityService(PiSeriesMapper series, PiQuantityMapper quantities, SkuInventoryMapper inventory,
            OrderReservationMapper reservations, OrderMapper orders, OperationLogMapper logs, ObjectMapper json, PiMapper pis) {
        this.series=series;this.quantities=quantities;this.inventory=inventory;this.reservations=reservations;
        this.orders=orders;this.logs=logs;this.json=json;this.pis=pis;
    }

    @Transactional(propagation=Propagation.MANDATORY)
    public void validate(long piId, Map<Long,Integer> requested) { plan(piId,requested); }

    @Transactional(propagation=Propagation.MANDATORY)
    public void apply(long piId, Map<Long,Integer> requested) {
        var changes=plan(piId,requested);
        // Reductions release their exact commitments first; increases then check both SKU and legacy model capacity.
        changes.sort(Comparator.comparingInt((Change c)->c.quantity()-c.before().orderedQty()).thenComparingLong(c->c.before().orderId()));
        for(var change:changes) {
            var before=change.before();int target=change.quantity();long orderId=before.orderId();
            var order=orders.selectForUpdate(orderId);
            if(target>before.orderedQty()) {
                int remaining=target-before.shippedQty();
                if(inventory.snapshot(before.skuId(),orderId).availableForOrder()<remaining)
                    fail("SKU_INVENTORY_NOT_ENOUGH","修订增加数量超过该SKU当前可用库存");
                if(reservations.snapshot(order.getModelId(),orderId).availableForOrder()<remaining)
                    fail("INVENTORY_NOT_ENOUGH","修订增加数量与其他车型锁定或订单占用冲突");
            }
            String status;
            if(target==0) {
                if(quantities.closeSku(orderId)!=1 || quantities.closeReservation(orderId)!=1)
                    fail("PI_QUANTITY_CONFLICT","未发子单关闭失败，请刷新重试");
                status="CANCELLED";
            } else {
                if(quantities.resizeSku(orderId,before.skuId(),target)!=1 || quantities.resizeOrder(orderId,target)!=1
                        || quantities.resizeReservation(orderId,target)!=1)
                    fail("PI_QUANTITY_CONFLICT","订单数量或占用已变化，请刷新重试");
                status=target==before.shippedQty()?"SHIPPED":before.shippedQty()>0?"PARTIAL_SHIPPED":"CONFIRMED";
            }
            if(orders.updateStatus(orderId,status)!=1)fail("PI_QUANTITY_CONFLICT","履约订单状态更新失败");
            try {
                if(logs.insert("PI_QUANTITY_REVISION","PI:"+piId,"确认PI数量修订",CurrentOperator.id(),CurrentOperator.username(),
                        json.writeValueAsString(before),json.writeValueAsString(Map.of("orderId",orderId,"skuId",before.skuId(),
                                "orderedQty",target,"shippedQty",before.shippedQty(),"status",status)),CurrentOperator.clientIp(),
                        "来源锁定转换历史保留；本次差额由PI修订确认")!=1)fail("PI_AUDIT_FAILED","PI数量修订审计失败");
            } catch(com.fasterxml.jackson.core.JsonProcessingException error) {fail("PI_AUDIT_FAILED","PI数量修订审计无法保存");}
        }
    }

    private List<Change> plan(long piId,Map<Long,Integer> requested) {
        var lines=series.fulfillment(piId);
        if(lines.isEmpty())return new ArrayList<>();
        // Legacy model orders have no exact SKU commitment to resize safely.
        if(lines.stream().anyMatch(line->line.skuId()==null)) {
            var pi=pis.selectById(piId);var original=new TreeMap<Long,Integer>();
            if(pi.getPreviousRevisionId()!=null)
                for(var item:pis.selectItems(pi.getPreviousRevisionId()))original.put(item.getSkuId(),item.getQuantity());
            if(!original.equals(requested))fail("PI_QUANTITY_LEGACY_ORDER","历史车型订单须先建立准确SKU履约关系后调整数量");
            return new ArrayList<>();
        }
        var grouped=new TreeMap<Long,List<PiFulfillmentLine>>();
        for(var line:lines)grouped.computeIfAbsent(line.skuId(),unused->new ArrayList<>()).add(line);
        if(!grouped.keySet().equals(requested.keySet()))fail("ORDER_SKU_MISMATCH","本次修订须保留原履约SKU；可调整其未发数量");
        var changes=new ArrayList<Change>();
        for(var entry:grouped.entrySet()) {
            long target=requested.get(entry.getKey());var existing=entry.getValue();
            long shipped=existing.stream().mapToLong(PiFulfillmentLine::shippedQty).sum();
            if(target<shipped)fail("PI_QUANTITY_BELOW_SHIPPED","修订数量不能少于该SKU实际已出库数量");
            long current=existing.stream().mapToLong(l->(long)l.orderedQty()-l.closedQty()).sum();
            long delta=target-current;
            if(delta==0)continue;
            // Child orders are currently single-SKU; explicitly reject ambiguous historical multi-line layouts.
            for(var line:existing) {
                if(lines.stream().filter(l->l.orderId()==line.orderId()).count()!=1
                        || inventory.reservationItems(line.orderId()).size()!=1)
                    fail("PI_QUANTITY_LEGACY_ORDER","履约子单不是单SKU，须先核对数量分配");
            }
            if(delta>0) {
                var chosen=existing.stream().filter(this::active).reduce((a,b)->b).orElseThrow(
                        ()->new BusinessException("ORDER_RESERVATION_EXPIRED","没有有效未关闭子单可增加数量，请先核对库存占用"));
                long total=chosen.orderedQty()+delta;
                if(total>Integer.MAX_VALUE)fail("PI_QUANTITY_CONFLICT","修订数量超过子单容量");
                changes.add(new Change(chosen,(int)total));
            } else {
                long reduction=-delta;
                for(var line:existing.reversed()) {
                    if(reduction==0)break;
                    if(line.remainingQty()==0)continue;
                    if(!active(line))fail("ORDER_RESERVATION_EXPIRED","数量修订不能自动恢复到期或已关闭占用，请先核对");
                    int decrease=(int)Math.min(reduction,line.remainingQty());
                    changes.add(new Change(line,line.orderedQty()-decrease));reduction-=decrease;
                }
                if(reduction!=0)fail("PI_QUANTITY_CONFLICT","剩余可调整数量不足");
            }
        }
        return changes;
    }

    private boolean active(PiFulfillmentLine line) {
        if(line.closedQty()!=0 || !Set.of("CONFIRMED","PARTIAL_SHIPPED","SHIPPED").contains(line.status()))return false;
        // The normal FULFILLED projection intentionally hides expiry, so consult the actual reservation too.
        var held=reservations.selectForUpdate(line.orderId());
        return held!=null && !"RELEASED".equals(held.holdMode()) && reservations.expiredForOrder(line.orderId())==0;
    }
    private record Change(PiFulfillmentLine before,int quantity) {}
    private static void fail(String code,String message){throw new BusinessException(code,message);}
}
