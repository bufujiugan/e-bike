package com.bike.server.order;

import com.bike.server.common.BusinessException;
import com.bike.server.common.CurrentOperator;
import com.bike.server.common.PageResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StockOutOrderOptionsService {
    private final StockOutOrderOptionsMapper mapper;
    public StockOutOrderOptionsService(StockOutOrderOptionsMapper mapper) { this.mapper = mapper; }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageResponse<StockOutOrderOption> list(long customerId, String keyword, long page, long pageSize) {
        CurrentOperator.requirePermission("STOCK_OUT_OPERATE");
        // Applies to ordinary Web warehouse staff and PDA alike; explicitly rejects agents.
        CurrentOperator.requireWorkstation("STOCK_OUT");
        if (customerId <= 0) throw new BusinessException("INVALID_PARAMETER", "请选择订单客户");
        String query = keyword == null || keyword.isBlank() ? null : keyword.trim();
        if (query != null && query.length() > 100) {
            throw new BusinessException("INVALID_PARAMETER", "订单搜索内容不能超过100字");
        }
        long current = Math.min(10_000_000, Math.max(1, page));
        long size = Math.min(100, Math.max(1, pageSize));
        return new PageResponse<>(mapper.selectPage(customerId, query, (current - 1) * size, size),
                current, size, mapper.count(customerId, query));
    }
}
