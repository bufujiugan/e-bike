package com.bike.server.part;

import com.bike.server.common.BusinessException;
import com.bike.server.common.CurrentOperator;
import com.bike.server.common.OperationLogMapper;
import com.bike.server.common.PageResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.Currency;

@Service
public class PartPricingServiceImpl implements PartPricingService {
    private static final int PAGE_SIZE = 20;

    private final PartPricingMapper mapper;
    private final OperationLogMapper audit;
    private final ObjectMapper json;

    public PartPricingServiceImpl(PartPricingMapper mapper, OperationLogMapper audit, ObjectMapper json) {
        this.mapper = mapper;
        this.audit = audit;
        this.json = json;
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<Price> listPrices(String keyword, long page) {
        requireManager();
        long current = Math.clamp(page, 1, 100_000);
        String search = keyword == null ? null : keyword.strip();
        return new PageResponse<>(mapper.selectPrices(search, false, (current - 1) * PAGE_SIZE),
                current, PAGE_SIZE, mapper.countPrices(search, false));
    }

    @Override
    @Transactional
    public Price updatePrice(long partId, Update request) {
        requireManager();
        validatePrice(request);
        // 锁定零件主记录，串行处理定价，并在同一事务内记录变更前后值。
        if (mapper.lockPart(partId) == null) throw new BusinessException("PART_NOT_FOUND", "零件不存在");
        Price previous = mapper.selectPrice(partId);
        mapper.upsertPrice(partId, request);
        Price saved = mapper.selectPrice(partId);
        if (saved == null || audit.insert("PART_PRICING", previous.partNo(), "维护零件价目",
                CurrentOperator.id(), CurrentOperator.username(), json.valueToTree(previous).toString(),
                json.valueToTree(saved).toString(), CurrentOperator.clientIp(), null) != 1) {
            throw new BusinessException("PART_PRICING_SAVE_FAILED", "零件价目保存失败，请重试");
        }
        return saved;
    }

    private static void requireManager() {
        CurrentOperator.requireInternalWebAccess();
        CurrentOperator.requirePermission("PART_PRICING_MANAGE");
    }

    private static void validatePrice(Update request) {
        if ((request.unitPrice() == null) != (request.currency() == null)
                || (Boolean.TRUE.equals(request.sellable()) && request.unitPrice() == null)) {
            throw new BusinessException("PART_PRICING_REQUIRED", "单价和币种须同时填写，可售零件必须填写价格");
        }
        if (request.currency() == null) return;
        try {
            Currency.getInstance(request.currency());
        } catch (IllegalArgumentException error) {
            throw new BusinessException("PART_CURRENCY_INVALID", "请输入有效的 ISO 4217 币种");
        }
    }
}
