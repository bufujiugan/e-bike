package com.bike.server.stock;

import com.bike.server.common.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class SkuInventoryService {
    private final SkuInventoryMapper mapper;
    public SkuInventoryService(SkuInventoryMapper mapper) { this.mapper=mapper; }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public PageResponse<SkuInventoryRow> list(Long modelId,long page,long pageSize) {
        CurrentOperator.requireAnyPermission("INVENTORY_READ","AGENT_INVENTORY_READ");
        CurrentOperator.requireWebAccess();
        Long agentId=null;
        if(CurrentOperator.hasRole("WEB_AGENT")) {
            agentId=CurrentOperator.agentId();
            if(agentId==null) throw new BusinessException("AGENT_NOT_BOUND","当前账号未绑定代理商");
        }
        long p=Math.min(10000000,Math.max(1,page)),size=Math.min(100,Math.max(1,pageSize));
        return new PageResponse<>(mapper.list(modelId,agentId,(p-1)*size,size),p,size,mapper.count(modelId,agentId));
    }
}
