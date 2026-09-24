package com.bike.server.lock;

import com.bike.server.common.*;
import com.bike.server.order.*;
import com.bike.server.production.ProductionGuard;
import com.bike.server.stock.SkuInventoryMapper;
import com.bike.server.system.SystemConfigMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validator;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SkuLockService {
    @org.springframework.beans.factory.annotation.Autowired private com.bike.server.common.CustomerScopeAccess customerScope;
    private final SkuLockMapper mapper;
    private final InventoryLockMapper locks;
    private final SkuInventoryMapper inventory;
    private final OrderReservationMapper reservations;
    private final OrderMapper orders;
    private final ProductionGuard guard;
    private final SystemConfigMapper config;
    private final ObjectMapper json;
    private final Validator validator;
    public SkuLockService(SkuLockMapper mapper,InventoryLockMapper locks,SkuInventoryMapper inventory,
            OrderReservationMapper reservations,OrderMapper orders,ProductionGuard guard,
            SystemConfigMapper config,ObjectMapper json,Validator validator) {
        this.mapper=mapper;this.locks=locks;this.inventory=inventory;this.reservations=reservations;
        this.orders=orders;this.guard=guard;this.config=config;this.json=json;this.validator=validator;
    }

    @Transactional
    public List<InventoryLockView> create(SkuLockRequest input) {
        CurrentOperator.requireInternalWebAccess();
        CurrentOperator.requirePermission("AGENT_LOCK_WRITE");
        if(input==null || !validator.validate(input).isEmpty())
            throw new BusinessException("INVALID_PARAMETER","SKU锁定参数不合法");
        var sorted=input.items().stream().sorted(Comparator.comparingLong(SkuLockRequest.Item::skuId)).toList();
        if(sorted.stream().map(SkuLockRequest.Item::skuId).distinct().count()!=sorted.size())
            throw new BusinessException("INVALID_PARAMETER","同一批锁定不能重复提交同一SKU");
        var request=new SkuLockRequest(input.requestId().trim(),input.customerId(),input.agentId(),
                input.expiresAt(),input.remark(),sorted);
        customerScope.requireCustomer(request.customerId());
        guard.lock();
        long operator=CurrentOperator.id();
        String hash=hash(request);
        var prior=mapper.request(operator,request.requestId());
        if(prior!=null) {
            if(!prior.payloadHash().equals(hash))
                throw new BusinessException("SKU_LOCK_REQUEST_CONFLICT","同一请求标识不能用于不同锁定内容");
            return mapper.locks(prior.id());
        }
        if(request.agentId()!=null && locks.lockAgentAccess(request.agentId())==null)
            throw new BusinessException("AGENT_NOT_FOUND","代理商不存在或已停用");
        if(orders.countEnabledCustomer(request.customerId())!=1)
            throw new BusinessException("CUSTOMER_NOT_FOUND","客户不存在或已停用");
        var now=mapper.now();
        var expires=request.expiresAt()==null?now.plusHours(defaultHours()):request.expiresAt();
        if(!expires.isAfter(now)||expires.isAfter(now.plusHours(720)))
            throw new BusinessException("INVALID_PARAMETER","锁定截止必须在未来720小时内");
        if(mapper.insertRequest(operator,request.requestId(),hash)!=1)
            throw new BusinessException("INVENTORY_LOCK_CREATE_FAILED","锁定请求保存失败");
        long requestId=mapper.request(operator,request.requestId()).id();
        for(var item:sorted) {
            Long modelId=inventory.lockSkuModel(item.skuId());
            if(modelId==null) throw new BusinessException("SKU_NOT_FOUND","SKU或车型不存在或已停用");
            if(inventory.snapshot(item.skuId(),0).availableForOrder()<item.quantity())
                throw new BusinessException("SKU_INVENTORY_NOT_ENOUGH","该SKU合格库存不足，其他SKU库存不能替代");
            if(reservations.snapshot(modelId,0).availableForOrder()<item.quantity())
                throw new BusinessException("INVENTORY_NOT_ENOUGH","车型库存已被现有SKU或历史车型承诺占用");
            String no="LK"+UUID.randomUUID().toString().replace("-","").substring(0,16).toUpperCase();
            if(mapper.insertLock(no,request,item.skuId(),modelId,item.quantity(),expires,operator,requestId)!=1
                    ||locks.insertDetail(no,modelId,item.quantity())!=1)
                throw new BusinessException("INVENTORY_LOCK_CREATE_FAILED","SKU锁定保存失败");
            if(locks.insertOperationLog(no,"SKU库存锁定",operator,CurrentOperator.username(),
                    "SKU:"+item.skuId()+"，数量:"+item.quantity(),CurrentOperator.clientIp())!=1)
                throw new BusinessException("INVENTORY_LOCK_CREATE_FAILED","SKU锁定审计保存失败");
        }
        return mapper.locks(requestId);
    }

    private long defaultHours() {
        try { long hours=Long.parseLong(config.selectValue("inventory.lock.hours")); return hours>0&&hours<=720?hours:48; }
        catch(RuntimeException ignored) { return 48; }
    }
    private String hash(SkuLockRequest request) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(json.writeValueAsString(request).getBytes(StandardCharsets.UTF_8))); }
        catch(Exception error) { throw new IllegalStateException("Cannot hash SKU lock request",error); }
    }
}
