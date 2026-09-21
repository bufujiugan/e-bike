package com.bike.server.pi;

public record PiFulfillmentLine(long orderId, String orderNo, String status, Long skuId,
        String skuCode, int orderedQty, int shippedQty, int closedQty, int remainingQty,
        String reservationStatus) {}
