package com.bike.server.trace;

import java.time.LocalDateTime;

public class TraceStockInRecord {
    private String stockInNo;
    private String warehouseName;
    private String locationName;
    private String status;
    private LocalDateTime operationTime;
    private String cancelReason;

    public String getStockInNo() { return stockInNo; }
    public void setStockInNo(String stockInNo) { this.stockInNo = stockInNo; }
    public String getWarehouseName() { return warehouseName; }
    public void setWarehouseName(String warehouseName) { this.warehouseName = warehouseName; }
    public String getLocationName() { return locationName; }
    public void setLocationName(String locationName) { this.locationName = locationName; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public LocalDateTime getOperationTime() { return operationTime; }
    public void setOperationTime(LocalDateTime operationTime) { this.operationTime = operationTime; }
    public String getCancelReason() { return cancelReason; }
    public void setCancelReason(String cancelReason) { this.cancelReason = cancelReason; }
}
