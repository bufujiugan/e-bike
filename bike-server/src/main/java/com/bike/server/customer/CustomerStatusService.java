package com.bike.server.customer;

import com.bike.server.common.PageResponse;
import com.bike.server.part.PartPricingService;
import java.time.LocalDateTime;
import java.util.List;

public interface CustomerStatusService {
    PageResponse<OrderSummary> listOrders(long page);
    OrderDetail getOrder(String kind, long id);
    void removeCancelledOrder(String kind, long id);
    PageResponse<Inventory> listInventory(long page);
    PageResponse<PartPricingService.Price> listParts(String keyword, long page);

    record OrderSummary(long id, String kind, String orderNo, String modelName, long orderQty,
                        long shippedQty, String status) {}
    record OrderDetail(OrderSummary order, List<Logistics> logistics) {}
    record Logistics(String batchNo, String carrier, String trackingNo, String status, LocalDateTime shippedAt) {}
    record Inventory(long modelId, String modelName, long quantity) {}
}
