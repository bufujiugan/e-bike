package com.bike.server.europe;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public record FactorySalesView(long id, String orderNo, String customerName, String warehouseName,
        int revision, FactorySalesRequest details, Map<String, StepConfirmation> steps,
        List<LinkedOrder> orders, LocalDateTime createdAt, LocalDateTime updatedAt) {
    public record StepConfirmation(String operatorName, LocalDateTime confirmedAt, String note) {}
    public record LinkedOrder(long id, String orderNo, int orderQty, int shippedQty, String status) {}
}
