package com.bike.server.europe;

import com.bike.server.common.*;
import com.bike.server.order.OrderMapper;
import com.bike.server.pi.*;
import com.bike.server.production.ProductionGuard;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validator;
import java.time.LocalDateTime;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EuropeSalesServiceImpl implements EuropeSalesService {
    private static final Set<String> STEPS = Set.of("CONTRACT", "PREPARATION", "PICKUP", "COLLECTION", "CLOSED");
    private final EuropeSalesMapper mapper;
    private final OrderMapper orders;
    private final PiMapper pis;
    private final PiSeriesMapper series;
    private final PiBusinessMapper warehouses;
    private final CustomerScopeAccess scope;
    private final ProductionGuard guard;
    private final OperationLogMapper audit;
    private final ObjectMapper json;
    private final Validator validator;

    public EuropeSalesServiceImpl(EuropeSalesMapper mapper, OrderMapper orders, PiMapper pis,
            PiSeriesMapper series, PiBusinessMapper warehouses, CustomerScopeAccess scope,
            ProductionGuard guard, OperationLogMapper audit, ObjectMapper json, Validator validator) {
        this.mapper=mapper; this.orders=orders; this.pis=pis; this.series=series; this.warehouses=warehouses;
        this.scope=scope; this.guard=guard; this.audit=audit; this.json=json; this.validator=validator;
    }

    @Override public PageResponse<FactorySalesView> listFactoryOrders(String keyword, Long warehouseId,
            String businessType, long page, long pageSize) {
        requireRead();
        long p=Math.min(1_000_000,Math.max(1,page)), size=Math.min(100,Math.max(1,pageSize));
        return new PageResponse<>(mapper.selectOrders(keyword,warehouseId,businessType,(p-1)*size,size)
                .stream().map(this::view).toList(),p,size,mapper.countOrders(keyword,warehouseId,businessType));
    }
    @Override public FactorySalesView getFactoryOrder(long id) { requireRead(); return view(requireOrder(id,false)); }

    @Override @Transactional public FactorySalesView createFactoryOrder(FactorySalesRequest request) {
        requireWrite(); validate(request); guard.lock(); scope.requireCustomer(request.customerId());
        var previous=mapper.selectByRequestId(request.requestId());
        if(previous!=null) {
            scope.requireCustomer(previous.customerId());
            if(!details(previous).equals(request)) fail("FACTORY_REQUEST_CONFLICT","同一请求已用于其他内容，请刷新订单核对");
            return view(previous);
        }
        validateReferences(request);
        String no="FS"+UUID.randomUUID().toString().replace("-","").substring(0,18).toUpperCase(Locale.ROOT);
        if(mapper.insertOrder(no,request,encode(request),CurrentOperator.id())!=1) fail("FACTORY_SAVE_FAILED","工厂直签订单保存失败");
        var saved=mapper.selectByRequestId(request.requestId());
        synchronizeOrderLinks(saved.id(),request);
        recordAudit("FACTORY_SALES",saved.id(),"建立工厂直签订单",null,request);
        return view(saved);
    }

    @Override @Transactional public FactorySalesView updateFactoryOrder(long id,FactorySalesRequest request) {
        requireWrite(); validate(request); guard.lock();
        var before=requireOrder(id,true);
        if(before.revision()!=request.revision()) conflict();
        if(!details(before).requestId().equals(request.requestId())) fail("FACTORY_REQUEST_CONFLICT","订单请求标识不可替换");
        validateReferences(request);
        // 款项发生后保持归属和币种稳定，避免把已有流水移到另一客户或币种。
        if(!mapper.selectSettlements("FACTORY",id).isEmpty() &&
                (!Objects.equals(before.customerId(),request.customerId()) || !before.currencyCode().equals(request.currencyCode())))
            fail("FACTORY_SETTLEMENT_IDENTITY","已有款项记录的订单不能更换客户或币种，请另建订单");
        var shipped=mapper.selectLinkedOrders(id).stream().filter(order->order.shippedQty()>0).toList();
        if(!shipped.isEmpty() && (!Objects.equals(before.customerId(),request.customerId())
                || !Objects.equals(before.warehouseId(),request.warehouseId())
                || !details(before).businessType().equals(request.businessType())
                || shipped.stream().anyMatch(order->request.orderIds()==null || !request.orderIds().contains(order.id()))))
            fail("FACTORY_SHIPPED_IDENTITY","已有实际出库，请保留原客户、仓库、业务类型和已出库订单关联；其他资料可继续补录");
        synchronizeOrderLinks(id,request);
        if(mapper.updateOrder(id,request,encode(request))!=1) conflict();
        var confirmations=steps(before);
        boolean changed=confirmations.remove("CLOSED")!=null;
        var prior=details(before);
        // 更换交易归属后原到账确认不再适用，但保留资料继续填写的能力。
        if(!Objects.equals(prior.customerId(),request.customerId()) || !Objects.equals(prior.currencyCode(),request.currencyCode())
                || !Objects.equals(prior.factoryName(),request.factoryName()) || !Objects.equals(prior.businessType(),request.businessType())) {
            changed |= confirmations.remove("COLLECTION")!=null;
            changed |= confirmations.remove("PICKUP")!=null;
        }
        if(changed && mapper.updateSteps(id,request.revision()+1,encode(confirmations))!=1) conflict();
        recordAudit("FACTORY_SALES",id,"补录或更正工厂直签资料",details(before),request);
        return view(requireOrder(id,false));
    }

    @Override @Transactional public FactorySalesView confirmFactoryStep(long id,String step,int revision,boolean completed,String note) {
        requireWrite();
        if(!STEPS.contains(step)) fail("FACTORY_STEP_INVALID","办理步骤不存在");
        guard.lock();
        var before=requireOrder(id,true);
        if(before.revision()!=revision) conflict();
        var confirmations=steps(before);
        if(completed && "PICKUP".equals(step) && !confirmations.containsKey("COLLECTION"))
            fail("FACTORY_COLLECTION_CONFIRM_REQUIRED","工厂直签先付款后取货，请先确认工厂已收款；凭证可后补");
        if(completed) confirmations.put(step,new FactorySalesView.StepConfirmation(CurrentOperator.username(),LocalDateTime.now(),note));
        else {
            confirmations.remove(step); confirmations.remove("CLOSED");
            if("COLLECTION".equals(step)) confirmations.remove("PICKUP");
        }
        // 这里只记录经办人的流程确认，不生成收款、付款、库存或出库事实。
        if(mapper.updateSteps(id,revision,encode(confirmations))!=1) conflict();
        recordAudit("FACTORY_SALES",id,(completed?"确认办理：":"恢复待办：")+step,steps(before),confirmations);
        return view(requireOrder(id,false));
    }

    private void validateReferences(FactorySalesRequest request) {
        scope.requireCustomer(request.customerId());
        if(orders.countEnabledCustomer(request.customerId())!=1) fail("CUSTOMER_NOT_FOUND","客户不存在或已停用");
        if(request.warehouseId()!=null && warehouses.countEnabledWarehouse(request.warehouseId())!=1)
            fail("WAREHOUSE_NOT_FOUND","所选仓库不存在或已停用");
    }
    private void synchronizeOrderLinks(long id,FactorySalesRequest request) {
        var desired=new TreeSet<>(request.orderIds()==null?List.of():request.orderIds());
        var existing=mapper.selectLinkedOrders(id);
        for(long orderId:desired) {
            var order=orders.selectForUpdate(orderId);
            if(order==null || !Objects.equals(order.getCustomerId(),request.customerId()))
                fail("FACTORY_ORDER_CUSTOMER_MISMATCH","关联销售订单须属于同一客户");
            scope.requireCustomer(order.getCustomerId());
            if(series.currentPiForOrder(orderId)!=null) fail("FACTORY_ORDER_HAS_PI","该销售订单已关联 PI，请使用正常 PI 销售流程");
            if(order.getSelectedSalesType()!=null && !com.bike.server.order.SalesType.EUROPE.equals(order.getSelectedSalesType()))
                fail("FACTORY_ORDER_SALES_TYPE_MISMATCH","该销售订单已确定为「"+com.bike.server.order.SalesType.label(order.getSelectedSalesType())+"」，不能关联欧洲工厂直签");
            Long owner=mapper.selectFactoryOrderForSalesOrder(orderId);
            if(owner!=null && owner!=id) fail("FACTORY_ORDER_LINK_CONFLICT","该销售订单已关联其他工厂直签订单");
            if(owner==null && mapper.insertOrderLink(id,orderId)!=1) conflict();
        }
        for(var linked:existing) if(!desired.contains(linked.id()) && mapper.deleteOrderLink(id,linked.id())!=1) conflict();
    }

    @Override public List<SalesSettlementView> listSettlements(String type,long subjectId) {
        requireFinanceRead(); var subject=settlementSubject(type,subjectId,false);
        return mapper.selectSettlements(type,subject.id());
    }
    @Override @Transactional public List<SalesSettlementView> recordSettlement(String type,long subjectId,SalesSettlementRequest request) {
        CurrentOperator.requireInternalWebAccess(); CurrentOperator.requirePermission("PI_PAYMENT_CONFIRM"); validate(request);
        var subject=settlementSubject(type,subjectId,true);
        if("PI".equals(type) && !Set.of("CONFIRMED","RELEASED").contains(subject.status()))
            fail("SETTLEMENT_PI_CONFIRM_REQUIRED","请先确认 PI 后再登记向 Totem 付款");
        if(!subject.currency().equals(request.currencyCode())) fail("SETTLEMENT_CURRENCY_MISMATCH","请按本单币种登记实际款项");
        if(request.occurredAt().isAfter(LocalDateTime.now().plusMinutes(1))) fail("SETTLEMENT_DATE_INVALID","实际款项日期不能晚于当前时间");
        var previous=mapper.selectSettlementRequest(type,subject.id(),request.requestId());
        if(previous!=null) {
            if(previous.amount().compareTo(request.amount())!=0 || !previous.currencyCode().equals(request.currencyCode())
                    || !previous.occurredAt().equals(request.occurredAt()) || !Objects.equals(previous.counterparty(),request.counterparty())
                    || !Objects.equals(previous.referenceNo(),request.referenceNo()) || !Objects.equals(previous.remark(),request.remark()))
                fail("SETTLEMENT_REQUEST_CONFLICT","同一请求已登记不同款项，请刷新核对");
        } else {
            if(mapper.insertSettlement(type,subject.id(),request,CurrentOperator.id())!=1) fail("SETTLEMENT_SAVE_FAILED","款项记录保存失败");
            recordAudit("SALES_SETTLEMENT",subject.id(),"PI".equals(type)?"登记向 Totem 付款":"登记工厂实际到账",null,request);
        }
        return mapper.selectSettlements(type,subject.id());
    }
    @Override @Transactional public List<SalesSettlementView> supplementSettlement(String type,long subjectId,long entryId,SalesSettlementSupplement request) {
        CurrentOperator.requireInternalWebAccess(); CurrentOperator.requirePermission("PI_PAYMENT_CONFIRM"); validate(request);
        var subject=settlementSubject(type,subjectId,true);
        var entry=mapper.selectSettlements(type,subject.id()).stream().filter(row->row.id()==entryId).findFirst()
                .orElseThrow(()->new BusinessException("SETTLEMENT_NOT_FOUND","本单没有该款项记录"));
        if(!"CONFIRMED".equals(entry.status())) fail("SETTLEMENT_REVERSED","已撤回记录保留原始内容，请在有效款项中补录");
        if(Objects.equals(entry.counterparty(),request.counterparty()) && Objects.equals(entry.referenceNo(),request.referenceNo())
                && Objects.equals(entry.remark(),request.remark())) return mapper.selectSettlements(type,subject.id());
        if(mapper.updateSettlementSupplement(type,subject.id(),entryId,request)!=1) conflict();
        recordAudit("SALES_SETTLEMENT",subject.id(),"补录款项参考信息",entry,Map.of("entryId",entryId,"subjectType",type,"supplement",request));
        return mapper.selectSettlements(type,subject.id());
    }
    @Override @Transactional public List<SalesSettlementView> reverseSettlement(String type,long subjectId,long entryId,String reason) {
        CurrentOperator.requireInternalWebAccess(); CurrentOperator.requirePermission("PI_PAYMENT_REVERSE");
        if(reason==null || reason.isBlank() || reason.length()>500) fail("SETTLEMENT_REASON_REQUIRED","请填写款项更正原因");
        if("FACTORY".equals(type)) guard.lock();
        var subject=settlementSubject(type,subjectId,true);
        var entry=mapper.selectSettlements(type,subject.id()).stream().filter(row->row.id()==entryId).findFirst()
                .orElseThrow(()->new BusinessException("SETTLEMENT_NOT_FOUND","本单没有该款项记录"));
        if("REVERSED".equals(entry.status())) return mapper.selectSettlements(type,subject.id());
        if(mapper.reverseSettlement(type,subject.id(),entryId,reason,CurrentOperator.id())!=1) conflict();
        if("FACTORY".equals(type)) {
            var order=requireOrder(subjectId,true);var confirmations=steps(order);
            confirmations.remove("COLLECTION");confirmations.remove("PICKUP");confirmations.remove("CLOSED");
            if(mapper.updateSteps(subjectId,order.revision(),encode(confirmations))!=1) conflict();
        }
        recordAudit("SALES_SETTLEMENT",subject.id(),"撤回错误款项登记",entry,Map.of("entryId",entryId,"reason",reason,"subjectType",type));
        return mapper.selectSettlements(type,subject.id());
    }

    private record SettlementSubject(long id,String currency,String status) {}
    private SettlementSubject settlementSubject(String type,long id,boolean lock) {
        if("FACTORY".equals(type)) { var order=requireOrder(id,lock); return new SettlementSubject(id,order.currencyCode(),null); }
        if(!"PI".equals(type)) throw new BusinessException("SETTLEMENT_TYPE_INVALID","款项类型不存在");
        var pi=pis.selectById(id);
        if(pi==null) throw new BusinessException("PI_NOT_FOUND","PI 不存在");
        scope.requireCustomer(pi.getCustomerId());
        // 所有修订使用同一系列的付款台账；系列行锁防止不同修订并发重复登记。
        if(pi.getSeriesId()==null) throw new BusinessException("PI_SERIES_REQUIRED","PI系列尚未建立，请先保存完整单据");
        long seriesId=pi.getSeriesId();
        if(lock) {
            series.lockSeries(seriesId);
            pi=pis.selectForUpdate(id);
            scope.requireCustomer(pi.getCustomerId());
        }
        return new SettlementSubject(seriesId,pi.getCurrencyCode(),pi.getStatus());
    }
    private EuropeSalesMapper.StoredOrder requireOrder(long id,boolean lock) {
        var order=mapper.selectOrder(id);
        if(order==null) throw new BusinessException("FACTORY_ORDER_NOT_FOUND","工厂直签订单不存在");
        scope.requireCustomer(order.customerId());
        if(lock) { order=mapper.selectOrderForUpdate(id); scope.requireCustomer(order.customerId()); }
        return order;
    }
    private FactorySalesView view(EuropeSalesMapper.StoredOrder order) {
        return new FactorySalesView(order.id(),order.orderNo(),order.customerName(),order.warehouseName(),order.revision(),details(order),steps(order),mapper.selectLinkedOrders(order.id()),order.createdAt(),order.updatedAt());
    }
    private FactorySalesRequest details(EuropeSalesMapper.StoredOrder order) {
        try { return json.readValue(order.detailsJson(),FactorySalesRequest.class); }
        catch(JsonProcessingException error) { throw new IllegalStateException("工厂直签资料读取失败",error); }
    }
    private LinkedHashMap<String,FactorySalesView.StepConfirmation> steps(EuropeSalesMapper.StoredOrder order) {
        try { return json.readValue(order.stepsJson(),new TypeReference<LinkedHashMap<String,FactorySalesView.StepConfirmation>>(){}); }
        catch(JsonProcessingException error) { throw new IllegalStateException("工厂直签进度读取失败",error); }
    }
    private String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch(JsonProcessingException error) { throw new IllegalStateException("欧洲销售记录序列化失败",error); }
    }
    private void recordAudit(String type,long id,String action,Object before,Object after) {
        if(audit.insert(type,Long.toString(id),action,CurrentOperator.id(),CurrentOperator.username(),before==null?null:encode(before),encode(after),CurrentOperator.clientIp(),null)!=1)
            fail("EUROPE_AUDIT_FAILED","操作记录保存失败");
    }
    private void validate(Object request) { if(request==null || !validator.validate(request).isEmpty()) fail("EUROPE_INPUT_INVALID","请核对客户、业务类型、金额及填写格式"); }
    private static void requireRead() { CurrentOperator.requireInternalWebAccess(); CurrentOperator.requirePermission("PI_READ"); }
    private static void requireWrite() { CurrentOperator.requireInternalWebAccess(); CurrentOperator.requirePermission("PI_WRITE"); }
    private static void requireFinanceRead() { CurrentOperator.requireInternalWebAccess(); CurrentOperator.requireAnyPermission("PI_PAYMENT_CONFIRM","PI_RELEASE","PI_PAYMENT_REVERSE"); }
    private static void conflict() { fail("EUROPE_REVISION_CONFLICT","记录已被其他操作更新，请刷新后再办理"); }
    private static void fail(String code,String message) { throw new BusinessException(code,message); }
}
