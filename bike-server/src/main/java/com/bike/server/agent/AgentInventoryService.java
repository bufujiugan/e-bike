package com.bike.server.agent;

import com.bike.server.common.PageResponse;
import com.bike.server.lock.InventoryLockService;
import com.bike.server.lock.InventoryLockView;
import com.bike.server.stock.InventoryRow;
import com.bike.server.stock.StockService;
import com.bike.server.common.CurrentOperator;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

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
        PageResponse<InventoryLockView> firstPage = lockService.list(null, 1, 100);
        Set<Long> modelIds = new LinkedHashSet<>();
        collectModelIds(firstPage, modelIds);
        long totalPages = (firstPage.total() + firstPage.pageSize() - 1) / firstPage.pageSize();
        for (long lockPage = 2; lockPage <= totalPages; lockPage++) {
            collectModelIds(lockService.list(null, lockPage, firstPage.pageSize()), modelIds);
        }
        Map<String, InventoryRow> rows = new LinkedHashMap<>();
        for (Long modelId : modelIds) {
            PageResponse<InventoryRow> modelRows = stockService.inventory(modelId, null, 1, 100);
            for (InventoryRow row : modelRows.records()) {
                String key = String.valueOf(row.getModelId()) + ":" + row.getWarehouseId() + ":" + row.getLocationId();
                rows.putIfAbsent(key, row);
            }
        }
        ArrayList<InventoryRow> values = new ArrayList<>(rows.values());
        long p = Math.max(1, page), s = Math.min(100, Math.max(1, pageSize));
        int from = (int) Math.min(values.size(), (p - 1) * s), to = (int) Math.min(values.size(), from + s);
        return new PageResponse<>(values.subList(from, to), p, s, values.size());
    }

    private static void collectModelIds(PageResponse<InventoryLockView> locks, Set<Long> modelIds) {
        for (InventoryLockView lock : locks.records()) {
            if (lock.getProductModelId() != null) modelIds.add(lock.getProductModelId());
        }
    }

    private static void requireAgentRole() {
        CurrentOperator.requireAnyRole("WEB_AGENT", "SUPER_ADMIN");
    }
}
