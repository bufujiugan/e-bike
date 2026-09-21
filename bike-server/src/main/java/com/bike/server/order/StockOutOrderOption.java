package com.bike.server.order;

/** Minimal warehouse selection data; contains no payment or reservation-hold details. */
public record StockOutOrderOption(long id, String orderNo, long customerId, String modelName,
        int orderQty, int shippedQty, String status) {}
