package com.bike.server.order;

public record DomesticOrderLine(long skuId, String skuCode, long modelId, String modelName,
        String productName, String specification, int orderedQty, int shippedQty, int closedQty) {}
