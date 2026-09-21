package com.bike.server.stock;

/** Signed capacity is deliberate: shortage must never be hidden by clamping. */
public record SkuInventorySnapshot(long physicalQty, long committedQty, long ownedQty) {
    public long availableForOrder() { return physicalQty - committedQty + ownedQty; }
}
