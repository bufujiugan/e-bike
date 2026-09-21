package com.bike.server.agent;

import com.bike.server.common.PageResponse;
import com.bike.server.lock.InventoryLockService;
import com.bike.server.lock.InventoryLockView;
import com.bike.server.stock.InventoryRow;
import com.bike.server.stock.StockService;
import com.bike.server.common.CurrentOperator;
import org.springframework.stereotype.Service;

@Service
public class AgentInventoryService {
    private final InventoryLockService lockService;
    private final StockService stockService;

    public AgentInventoryService(InventoryLockService lockService, StockService stockService) {
        this.lockService = lockService;
        this.stockService = stockService;
    }

    public PageResponse<InventoryLockView> locks(long page, long pageSize) { requireAgentRole(); return lockService.list(null, page, pageSize); }

    public PageResponse<InventoryRow> inventory(long page, long pageSize) {
        requireAgentRole();
        return stockService.inventory(null, null, page, pageSize);
    }

    private static void requireAgentRole() {
        CurrentOperator.requireAnyRole("WEB_AGENT", "SUPER_ADMIN");
    }
}
