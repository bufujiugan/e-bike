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
    @org.springframework.beans.factory.annotation.Autowired private com.bike.server.lock.InventoryLockMapper locks;
    @org.springframework.beans.factory.annotation.Autowired private com.bike.server.lock.LockOrderService converter;
    @org.springframework.beans.factory.annotation.Autowired private CustomerScopeAccess customerScope;

    public PiQuantityService(PiSeriesMapper series, PiQuantityMapper quantities, SkuInventoryMapper inventory,
            OrderReservationMapper reservations, OrderMapper orders, OperationLogMapper logs, ObjectMapper json, PiMapper pis) {
        this.series=series;this.quantities=quantities;this.inventory=inventory;this.reservations=reservations;
        this.orders=orders;this.logs=logs;this.json=json;this.pis=pis;
    }

    @Transactional(propagation=Propagation.MANDATORY)
    public void validate(long piId, Map<Long,Integer> requested) { validate(piId,requested,List.of()); }

    @Transactional(propagation=Propagation.MANDATORY)
    public void validate(long piId, Map<Long,Integer> requested, List<PiConversionRequest.Source> sources) {
        var selected=resolveSources(piId,sources);
        plan(piId,withoutSources(requested,selected));
    }

    public List<PiRevisionSourceView> sources(long piId) { return quantities.sources(piId); }

    @Transactional(propagation=Propagation.MANDATORY)
    public void saveSources(long piId,List<PiConversionRequest.Source> sources) {
        var selected=resolveSources(piId,sources);
        if(quantities.sources(piId).stream().anyMatch(s->s.orderId()!=null))fail("PI_REVISION_CONFLICT","已确认的来源选择不能覆盖");
        quantities.clearSources(piId);
        for(var source:selected)if(quantities.insertSource(piId,source)!=1)fail("PI_REVISION_FAILED","新增SKU来源保存失败");
    }

    @Transactional(propagation=Propagation.MANDATORY)
    public void apply(long piId, Map<Long,Integer> requested) {
        var selected=quantities.sources(piId);
        if(!selected.isEmpty()) {
            var live=resolveSources(piId,selected.stream().map(s->new PiConversionRequest.Source(s.lockId(),s.quantity())).toList());
            for(int i=0;i<live.size();i++) {
                var before=selected.get(i);var now=live.get(i);
                if(before.orderId()!=null || before.skuId()!=now.skuId() || !before.expiresAt().equals(now.expiresAt()))
                    fail("PI_REVISION_SOURCE_CHANGED","新增SKU来源已变化，请重新核对并保存草稿");
            }
        }
        var changes=plan(piId,withoutSources(requested,selected));
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
        var pi=pis.selectById(piId);
        for(var source:selected) {
            var order=converter.convert(source.lockId(),new com.bike.server.lock.LockOrderRequest(pi.getCustomerId(),source.quantity(),
                    "PI_REV_"+UUID.randomUUID(),pi.getPlannedShipDate()));
            if(series.link(piId,order.getId(),"SKU_REVISION")!=1
                    || quantities.convertedSource(piId,source.lockId(),order.getId())!=1
                    || orders.updateStatus(order.getId(),"CONFIRMED")!=1)
                fail("PI_REVISION_FAILED","新增SKU履约关联或确认失败");
            try {
                if(logs.insert("PI_QUANTITY_REVISION","PI:"+piId,"确认PI新增SKU来源",CurrentOperator.id(),CurrentOperator.username(),
                        json.writeValueAsString(source),json.writeValueAsString(Map.of("orderId",order.getId(),"skuId",source.skuId(),
                                "quantity",source.quantity(),"sourceLockId",source.lockId())),CurrentOperator.clientIp(),
                        "沿用所选来源截止时间；新增占用随修订确认")!=1)fail("PI_AUDIT_FAILED","PI新增SKU审计失败");
            } catch(com.fasterxml.jackson.core.JsonProcessingException error){fail("PI_AUDIT_FAILED","PI新增SKU审计无法保存");}
        }
    }

    private List<PiRevisionSourceView> resolveSources(long piId,List<PiConversionRequest.Source> sources) {
        if(sources==null || sources.isEmpty())return List.of();
        CurrentOperator.requireInternalWebAccess();
        for(String permission:List.of("PI_WRITE","ORDER_WRITE","AGENT_LOCK_WRITE"))CurrentOperator.requirePermission(permission);
        var pi=pis.selectById(piId);
        if(pi==null || pi.getPreviousRevisionId()==null || !"DRAFT".equals(pi.getStatus()))
            fail("PI_REVISION_SOURCE_INVALID","只有修订草稿可以选择新增SKU来源");
        customerScope.requireCustomer(pi.getCustomerId());
        if(series.fulfillment(piId).isEmpty())fail("PI_REVISION_SOURCE_INVALID","须先建立完整履约关系后选择增量来源");
        if(sources.size()>100)fail("INVALID_PARAMETER","新增来源最多100条");
        var seen=new HashSet<Long>();var result=new ArrayList<PiRevisionSourceView>();
        for(var source:sources.stream().sorted(Comparator.comparingLong(PiConversionRequest.Source::lockId)).toList()) {
            if(source.lockId()<=0 || source.quantity()<1 || source.quantity()>1000000 || !seen.add(source.lockId()))
                fail("INVALID_PARAMETER","新增来源或数量无效，不能重复选择同一锁定");
            var lock=locks.selectById(source.lockId());
            if(lock==null)fail("INVENTORY_LOCK_NOT_FOUND","新增来源锁定不存在");
            customerScope.requireCustomer(lock.getCustomerId());
            if(!Objects.equals(pi.getCustomerId(),lock.getCustomerId()) || !Objects.equals(pi.getAgentId(),lock.getAgentId()))
                fail("LOCK_CUSTOMER_MISMATCH","新增来源必须属于同一客户和代理商");
            if(!"SKU".equals(lock.getScopeType()) || lock.getSkuId()==null)
                fail("PI_SKU_LOCK_REQUIRED","新增来源须为明确SKU的锁定");
            if(!Set.of("LOCKED","PARTIAL_CONVERTED").contains(lock.getStatus()) || lock.getExpireTime()==null
                    || !lock.getExpireTime().isAfter(java.time.LocalDateTime.now())
                    || source.quantity()>(long)lock.getLockQty()-lock.getConvertedQty()-lock.getReleasedQty())
                fail("PI_REVISION_SOURCE_UNAVAILABLE","新增来源已到期、关闭或剩余数量不足，请重新选择");
            var sku=pis.selectEnabledSku(lock.getSkuId());
            if(sku==null)fail("SKU_NOT_FOUND","新增SKU或所属车型不存在或已停用");
            result.add(new PiRevisionSourceView(lock.getId(),lock.getSkuId(),source.quantity(),lock.getLockNo(),sku.skuCode(),
                    sku.productName(),lock.getExpireTime(),null));
        }
        return result;
    }

    private Map<Long,Integer> withoutSources(Map<Long,Integer> requested,List<PiRevisionSourceView> sources) {
        var remaining=new TreeMap<>(requested);
        for(var source:sources) {
            Integer quantity=remaining.get(source.skuId());
            if(quantity==null || quantity<source.quantity())fail("ORDER_SKU_MISMATCH","新增来源数量不能超过修订中对应SKU的数量");
            remaining.put(source.skuId(),quantity-source.quantity());
        }
        return remaining;
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
        for(var entry:requested.entrySet())if(!grouped.containsKey(entry.getKey()) && entry.getValue()!=0)
            fail("PI_REVISION_SOURCE_REQUIRED","新增SKU须选择足额的同客户有效来源锁定");
        var changes=new ArrayList<Change>();
        for(var entry:grouped.entrySet()) {
            long target=requested.getOrDefault(entry.getKey(),0);var existing=entry.getValue();
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
