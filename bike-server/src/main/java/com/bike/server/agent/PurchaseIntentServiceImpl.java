package com.bike.server.agent;

import com.bike.server.common.BusinessException;
import com.bike.server.common.CurrentOperator;
import com.bike.server.common.OperationLogMapper;
import com.bike.server.common.PageResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PurchaseIntentServiceImpl implements PurchaseIntentService {
    private final PurchaseIntentMapper mapper;
    private final OperationLogMapper operationLogMapper;

    public PurchaseIntentServiceImpl(PurchaseIntentMapper mapper) {
        this(mapper, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public PurchaseIntentServiceImpl(PurchaseIntentMapper mapper, OperationLogMapper operationLogMapper) {
        this.mapper = mapper;
        this.operationLogMapper = operationLogMapper;
    }

    @Override
    public PageResponse<PurchaseIntentView> list(long page, long pageSize) {
        CurrentOperator.requireAnyRole("WEB_AGENT", "SUPER_ADMIN");
        CurrentOperator.requirePermission("AGENT_INVENTORY_READ");
        Long agentId = CurrentOperator.hasRole("WEB_AGENT") ? requireAgent() : null;
        long normalizedPage = Math.max(1, page);
        long normalizedSize = Math.min(100, Math.max(1, pageSize));
        return new PageResponse<>(mapper.selectPage(agentId, (normalizedPage - 1) * normalizedSize, normalizedSize),
                normalizedPage, normalizedSize, mapper.count(agentId));
    }

    @Override
    @Transactional
    public PurchaseIntentView create(PurchaseIntentCreateRequest request) {
        CurrentOperator.requireAnyRole("WEB_AGENT", "SUPER_ADMIN");
        CurrentOperator.requirePermission("PURCHASE_INTENT_WRITE");
        Long requestedAgentId = request.agentId();
        long agentId = CurrentOperator.hasRole("WEB_AGENT") ? requireAgent() : requestedAgentId == null ? 0L : requestedAgentId;
        if (agentId <= 0) throw new BusinessException("AGENT_REQUIRED", "代理商不能为空");
        if (agentId <= 0 || request.productModelId() == null || mapper.countAuthorizedModel(agentId, request.productModelId()) == 0) {
            throw new BusinessException("MODEL_NOT_AUTHORIZED", "当前代理商未授权该车型或授权已失效");
        }
        if (request.expireTime() != null && !request.expireTime().isAfter(java.time.LocalDateTime.now())) {
            throw new BusinessException("INVALID_PARAMETER", "采购意向有效期必须晚于当前时间");
        }
        if (mapper.insert(request, agentId, CurrentOperator.id()) != 1) {
            throw new BusinessException("PURCHASE_INTENT_CREATE_FAILED", "采购意向创建失败");
        }
        long intentId = mapper.selectLatestId(agentId);
        if (operationLogMapper != null) {
            operationLogMapper.insert("PURCHASE_INTENT", String.valueOf(intentId), "提交采购意向",
                    CurrentOperator.id(), CurrentOperator.username(), null,
                    "{\"agentId\":" + agentId + ",\"productModelId\":" + request.productModelId()
                            + ",\"intentQty\":" + request.intentQty() + "}", CurrentOperator.clientIp(), request.remark());
        }
        return mapper.selectById(intentId);
    }

    private static long requireAgent() {
        Long agentId = CurrentOperator.agentId();
        if (agentId == null) throw new BusinessException("AGENT_NOT_BOUND", "当前账号未绑定代理商");
        return agentId;
    }
}
