package com.bike.server.vehicle;

import java.time.LocalDateTime;

public class VehicleView {
    private Long id;
    private String frameSn;
    private Long modelId;
    private String modelName;
    private Integer status;
    private String statusName;
    private String vehicleQrCode;
    private String vehicleQrToken;
    private LocalDateTime qrGeneratedAt;
    private Integer qrVersion;
    private Long assembleOperatorId;
    private String assembleOperatorName;
    private LocalDateTime assembleTime;
    private Long currentWarehouseId;
    private String warehouseName;
    private Long currentLocationId;
    private String locationName;
    private LocalDateTime stockInTime;
    private LocalDateTime stockOutTime;
    private Long currentCustomerId;
    private String customerName;
    private String currentOrderNo;
    private String motorSn;
    private String batterySn;
    private String displaySn;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getFrameSn() { return frameSn; }
    public void setFrameSn(String frameSn) { this.frameSn = frameSn; }
    public Long getModelId() { return modelId; }
    public void setModelId(Long modelId) { this.modelId = modelId; }
    public String getModelName() { return modelName; }
    public void setModelName(String modelName) { this.modelName = modelName; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
    public String getStatusName() { return statusName; }
    public void setStatusName(String statusName) { this.statusName = statusName; }
    public String getVehicleQrCode() { return vehicleQrCode; }
    public void setVehicleQrCode(String vehicleQrCode) { this.vehicleQrCode = vehicleQrCode; }
    public String getVehicleQrToken() { return vehicleQrToken; }
    public void setVehicleQrToken(String vehicleQrToken) { this.vehicleQrToken = vehicleQrToken; }
    public LocalDateTime getQrGeneratedAt() { return qrGeneratedAt; }
    public void setQrGeneratedAt(LocalDateTime qrGeneratedAt) { this.qrGeneratedAt = qrGeneratedAt; }
    public Integer getQrVersion() { return qrVersion; }
    public void setQrVersion(Integer qrVersion) { this.qrVersion = qrVersion; }
    public Long getAssembleOperatorId() { return assembleOperatorId; }
    public void setAssembleOperatorId(Long assembleOperatorId) { this.assembleOperatorId = assembleOperatorId; }
    public String getAssembleOperatorName() { return assembleOperatorName; }
    public void setAssembleOperatorName(String assembleOperatorName) { this.assembleOperatorName = assembleOperatorName; }
    public LocalDateTime getAssembleTime() { return assembleTime; }
    public void setAssembleTime(LocalDateTime assembleTime) { this.assembleTime = assembleTime; }
    public Long getCurrentWarehouseId() { return currentWarehouseId; }
    public void setCurrentWarehouseId(Long currentWarehouseId) { this.currentWarehouseId = currentWarehouseId; }
    public String getWarehouseName() { return warehouseName; }
    public void setWarehouseName(String warehouseName) { this.warehouseName = warehouseName; }
    public Long getCurrentLocationId() { return currentLocationId; }
    public void setCurrentLocationId(Long currentLocationId) { this.currentLocationId = currentLocationId; }
    public String getLocationName() { return locationName; }
    public void setLocationName(String locationName) { this.locationName = locationName; }
    public LocalDateTime getStockInTime() { return stockInTime; }
    public void setStockInTime(LocalDateTime stockInTime) { this.stockInTime = stockInTime; }
    public LocalDateTime getStockOutTime() { return stockOutTime; }
    public void setStockOutTime(LocalDateTime stockOutTime) { this.stockOutTime = stockOutTime; }
    public Long getCurrentCustomerId() { return currentCustomerId; }
    public void setCurrentCustomerId(Long currentCustomerId) { this.currentCustomerId = currentCustomerId; }
    public String getCustomerName() { return customerName; }
    public void setCustomerName(String customerName) { this.customerName = customerName; }
    public String getCurrentOrderNo() { return currentOrderNo; }
    public void setCurrentOrderNo(String currentOrderNo) { this.currentOrderNo = currentOrderNo; }
    public String getMotorSn() { return motorSn; }
    public void setMotorSn(String motorSn) { this.motorSn = motorSn; }
    public String getBatterySn() { return batterySn; }
    public void setBatterySn(String batterySn) { this.batterySn = batterySn; }
    public String getDisplaySn() { return displaySn; }
    public void setDisplaySn(String displaySn) { this.displaySn = displaySn; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
