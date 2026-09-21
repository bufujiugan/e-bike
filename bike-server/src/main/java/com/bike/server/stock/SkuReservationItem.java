package com.bike.server.stock;

public record SkuReservationItem(long skuId, int orderedQty, int shippedQty, int closedQty) {
    public int remainingQty() { return orderedQty - shippedQty - closedQty; }
}
