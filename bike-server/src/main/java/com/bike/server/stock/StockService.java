package com.bike.server.stock;

import com.bike.server.common.PageResponse;
import java.util.Map;

public interface StockService {
    StockValidationResult validateStockIn(StockValidationRequest request);
    StockValidationResult validateStockOut(StockValidationRequest request);
    StockRecordView stockIn(StockInRequest request);
    StockRecordView cancelStockIn(long id, String reason);
    StockRecordView stockOut(StockOutRequest request);
    StockRecordView cancelStockOut(long id, String reason);
    PageResponse<StockRecordView> list(String type, long page, long pageSize);
    PageResponse<InventoryRow> inventory(Long modelId, Long warehouseId, long page, long pageSize);
    Map<String, Long> inventorySummary();
}
