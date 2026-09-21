package com.bike.server.stock;

public class InventoryRow {
    // Repeated model-wide context: never sum these fields across warehouse/location rows.
    private long skuShortageQty;
    private long legacyModelCommittedQty;
    private long modelNewCommitmentQty;
    public long getSkuShortageQty(){return skuShortageQty;}
    public void setSkuShortageQty(long value){skuShortageQty=value;}
    public long getLegacyModelCommittedQty(){return legacyModelCommittedQty;}
    public void setLegacyModelCommittedQty(long value){legacyModelCommittedQty=value;}
    public long getModelNewCommitmentQty(){return modelNewCommitmentQty;}
    public void setModelNewCommitmentQty(long value){modelNewCommitmentQty=value;}
    private Long modelId;
    private String modelName;
    private Long warehouseId;
    private String warehouseName;
    private Long locationId;
    private String locationName;
    private long physicalQty;
    private long lockedQty;
    private long temporaryLockedQty;
    private long orderReservedQty;
    private long shortageQty;
    public long getTemporaryLockedQty(){return temporaryLockedQty;}
    public void setTemporaryLockedQty(long value){temporaryLockedQty=value;}
    public long getOrderReservedQty(){return orderReservedQty;}
    public void setOrderReservedQty(long value){orderReservedQty=value;}
    public long getShortageQty(){return shortageQty;}
    public void setShortageQty(long value){shortageQty=value;}
    private long availableQty;
    private long quarantinedQty;
    public long getQuarantinedQty() { return quarantinedQty; }
    public void setQuarantinedQty(long value) { quarantinedQty = value; }
    private long ageDays;
    public Long getModelId() { return modelId; }
    public void setModelId(Long modelId) { this.modelId = modelId; }
    public String getModelName() { return modelName; }
    public void setModelName(String modelName) { this.modelName = modelName; }
    public Long getWarehouseId() { return warehouseId; }
    public void setWarehouseId(Long warehouseId) { this.warehouseId = warehouseId; }
    public String getWarehouseName() { return warehouseName; }
    public void setWarehouseName(String warehouseName) { this.warehouseName = warehouseName; }
    public Long getLocationId() { return locationId; }
    public void setLocationId(Long locationId) { this.locationId = locationId; }
    public String getLocationName() { return locationName; }
    public void setLocationName(String locationName) { this.locationName = locationName; }
    public long getPhysicalQty() { return physicalQty; }
    public void setPhysicalQty(long physicalQty) { this.physicalQty = physicalQty; }
    public long getLockedQty() { return lockedQty; }
    public void setLockedQty(long lockedQty) { this.lockedQty = lockedQty; }
    public long getAvailableQty() { return availableQty; }
    public void setAvailableQty(long availableQty) { this.availableQty = availableQty; }
    public long getAgeDays() { return ageDays; }
    public void setAgeDays(long ageDays) { this.ageDays = ageDays; }
}
