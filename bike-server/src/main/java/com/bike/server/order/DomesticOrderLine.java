package com.bike.server.order;

import org.apache.ibatis.annotations.AutomapConstructor;

public record DomesticOrderLine(long skuId, String skuCode, long modelId, String modelName,
        String productName, String specification, int orderedQty, int shippedQty, int closedQty,
        String customsModel) {
    @AutomapConstructor
    public DomesticOrderLine {}

    public DomesticOrderLine(long skuId, String skuCode, long modelId, String modelName,
            String productName, String specification, int orderedQty, int shippedQty, int closedQty) {
        this(skuId, skuCode, modelId, modelName, productName, specification, orderedQty, shippedQty, closedQty, null);
    }
}
