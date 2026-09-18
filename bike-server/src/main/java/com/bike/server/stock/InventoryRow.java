package com.bike.server.stock;

public class InventoryRow {
    private Long modelId;
    private String modelName;
    private Long warehouseId;
    private String warehouseName;
    private Long locationId;
    private String locationName;
    private long physicalQty;
    private long lockedQty;
    private long availableQty;
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
