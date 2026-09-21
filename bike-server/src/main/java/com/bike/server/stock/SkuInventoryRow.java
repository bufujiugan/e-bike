package com.bike.server.stock;

public record SkuInventoryRow(long skuId, long modelId, String skuCode, String productName,
        String specification, String modelName, long physicalQty, long quarantinedQty,
        long temporaryLockedQty, long orderReservedQty, long availableQty, long shortageQty,
        long legacyModelCommittedQty, long modelAvailableQty, long maxNewLockQty) {}
