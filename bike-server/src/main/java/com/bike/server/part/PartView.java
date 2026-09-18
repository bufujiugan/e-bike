package com.bike.server.part;

import java.time.LocalDateTime;

public class PartView {
    private Long id;
    private String partSn;
    private String partType;
    private Long supplierId;
    private String supplierName;
    private String batchNo;
    private String qcStatus;
    private String bindStatus;
    private Long currentVehicleId;
    private String currentFrameSn;
    private String status;
    private LocalDateTime inboundTime;
    private String remark;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getPartSn() { return partSn; }
    public void setPartSn(String partSn) { this.partSn = partSn; }
    public String getPartType() { return partType; }
    public void setPartType(String partType) { this.partType = partType; }
    public Long getSupplierId() { return supplierId; }
    public void setSupplierId(Long supplierId) { this.supplierId = supplierId; }
    public String getSupplierName() { return supplierName; }
    public void setSupplierName(String supplierName) { this.supplierName = supplierName; }
    public String getBatchNo() { return batchNo; }
    public void setBatchNo(String batchNo) { this.batchNo = batchNo; }
    public String getQcStatus() { return qcStatus; }
    public void setQcStatus(String qcStatus) { this.qcStatus = qcStatus; }
    public String getBindStatus() { return bindStatus; }
    public void setBindStatus(String bindStatus) { this.bindStatus = bindStatus; }
    public Long getCurrentVehicleId() { return currentVehicleId; }
    public void setCurrentVehicleId(Long currentVehicleId) { this.currentVehicleId = currentVehicleId; }
    public String getCurrentFrameSn() { return currentFrameSn; }
    public void setCurrentFrameSn(String currentFrameSn) { this.currentFrameSn = currentFrameSn; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public LocalDateTime getInboundTime() { return inboundTime; }
    public void setInboundTime(LocalDateTime inboundTime) { this.inboundTime = inboundTime; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
