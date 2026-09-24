package com.bike.server.pi;

import com.bike.server.common.*;
import com.bike.server.lock.*;
import com.bike.server.production.ProductionGuard;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validator;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PiConversionService {
    @org.springframework.beans.factory.annotation.Autowired private com.bike.server.common.CustomerScopeAccess customerScope;
    private final InventoryLockMapper locks;
    private final LockOrderService converter;
    private final PiService pis;
    private final PiSeriesMapper series;
    private final ProductionGuard guard;
    private final ObjectMapper json;
    private final Validator validator;
    public PiConversionService(InventoryLockMapper locks, LockOrderService converter, PiService pis,
            PiSeriesMapper series, ProductionGuard guard, ObjectMapper json, Validator validator) {
        this.locks=locks; this.converter=converter; this.pis=pis; this.series=series;
        this.guard=guard; this.json=json; this.validator=validator;
    }
    @Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public PageResponse<Map<String,Object>> options(long customerId,Long agentId,String keyword,long page) {
        CurrentOperator.requireInternalWebAccess();
        for(String permission:List.of("PI_READ","PI_WRITE","AGENT_LOCK_WRITE"))CurrentOperator.requirePermission(permission);
        customerScope.requireCustomer(customerId);
        long p=Math.min(10000000,Math.max(1,page));
        String query=keyword==null||keyword.isBlank()?null:keyword.trim();
        return new PageResponse<>(series.lockOptions(customerId,agentId,query,(p-1)*20),p,20,series.countLockOptions(customerId,agentId,query));
    }
    @Transactional
    public PiView convert(PiConversionRequest input) {
        CurrentOperator.requireInternalWebAccess();
        for(String permission:List.of("PI_READ","PI_WRITE","ORDER_WRITE","AGENT_LOCK_WRITE")) CurrentOperator.requirePermission(permission);
        if(input==null||!validator.validate(input).isEmpty()) fail("INVALID_PARAMETER","PI转换参数不合法");
        if(input.pi().sourceOrderId()!=null) fail("INVALID_PARAMETER","锁定转换由系统生成履约订单，请勿另填来源订单");
        var sources=input.sources().stream().sorted(Comparator.comparingLong(PiConversionRequest.Source::lockId)).toList();
        if(sources.stream().map(PiConversionRequest.Source::lockId).distinct().count()!=sources.size()) fail("INVALID_PARAMETER","同一锁定不能重复提交");
        var request=new PiConversionRequest(input.requestId().trim(),sources,input.pi());
        customerScope.requireCustomer(request.pi().customerId());
        guard.lock();
        String hash=hash(request);
        var prior=series.conversion(CurrentOperator.id(),request.requestId());
        if(prior!=null) {
            if(!prior.payloadHash().equals(hash)) fail("PI_CONVERSION_CONFLICT","同一转换标识不能用于不同内容");
            return pis.get(prior.piId());
        }
        var expected=new TreeMap<Long,Long>();
        for(var source:sources) {
            var lock=locks.selectById(source.lockId());
            if(lock==null) fail("INVENTORY_LOCK_NOT_FOUND","来源锁定不存在");
            if(lock.getSkuId()==null||!"SKU".equals(lock.getScopeType())) fail("PI_SKU_LOCK_REQUIRED","原子转换须使用明确客户和SKU的锁定");
            if(!Objects.equals(lock.getCustomerId(),request.pi().customerId())||!Objects.equals(lock.getAgentId(),request.pi().agentId()))
                fail("LOCK_CUSTOMER_MISMATCH","所有锁定必须属于PI所选的同一客户与代理商");
            expected.merge(lock.getSkuId(),(long)source.quantity(),Long::sum);
        }
        var actual=new TreeMap<Long,Long>();
        for(var item:request.pi().items()) actual.merge(item.skuId(),(long)item.quantity(),Long::sum);
        if(!expected.equals(actual)) fail("ORDER_SKU_MISMATCH","PI各SKU数量必须等于本次来源锁定转换量");
        var orders=new ArrayList<Long>();
        for(var source:sources) orders.add(converter.convert(source.lockId(),new LockOrderRequest(request.pi().customerId(),source.quantity(),
                "PI_"+UUID.randomUUID(),request.pi().plannedShipDate())).getId());
        // Service proxy calls join this transaction: a PI validation/audit failure rolls every order back.
        PiView pi=pis.create(request.pi());
        for(long orderId:orders) if(series.link(pi.getId(),orderId,"SKU_CONVERSION")!=1) fail("PI_CONVERSION_FAILED","履约订单关联失败");
        if(series.saveConversion(CurrentOperator.id(),request.requestId(),hash,pi.getId())!=1) fail("PI_CONVERSION_FAILED","转换幂等记录保存失败");
        return pis.get(pi.getId());
    }
    private String hash(PiConversionRequest request) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(request)));}
        catch(Exception error){throw new IllegalStateException("Cannot hash PI conversion",error);}
    }
    private static void fail(String code,String message){throw new BusinessException(code,message);}
}
