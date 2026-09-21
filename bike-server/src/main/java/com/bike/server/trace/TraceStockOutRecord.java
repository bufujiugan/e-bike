package com.bike.server.trace;

import java.time.LocalDateTime;

public class TraceStockOutRecord {
    private String piNo,dispatchBatchNo,dispatchSourceType;
    private Integer executionRevision;
    private String logisticsCarrier,logisticsTrackingNo,logisticsStatus;
    public String getLogisticsCarrier(){return logisticsCarrier;} public void setLogisticsCarrier(String value){logisticsCarrier=value;}
    public String getLogisticsTrackingNo(){return logisticsTrackingNo;} public void setLogisticsTrackingNo(String value){logisticsTrackingNo=value;}
    public String getLogisticsStatus(){return logisticsStatus;} public void setLogisticsStatus(String value){logisticsStatus=value;}
    public String getPiNo(){return piNo;} public void setPiNo(String value){piNo=value;}
    public String getDispatchBatchNo(){return dispatchBatchNo;} public void setDispatchBatchNo(String value){dispatchBatchNo=value;}
    public String getDispatchSourceType(){return dispatchSourceType;} public void setDispatchSourceType(String value){dispatchSourceType=value;}
    public Integer getExecutionRevision(){return executionRevision;} public void setExecutionRevision(Integer value){executionRevision=value;}
    private String stockOutNo;
    private String customerName;
    private String orderNo;
    private String status;
    private LocalDateTime operationTime;
    private String cancelReason;

    public String getStockOutNo() { return stockOutNo; }
    public void setStockOutNo(String stockOutNo) { this.stockOutNo = stockOutNo; }
    public String getCustomerName() { return customerName; }
    public void setCustomerName(String customerName) { this.customerName = customerName; }
    public String getOrderNo() { return orderNo; }
    public void setOrderNo(String orderNo) { this.orderNo = orderNo; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public LocalDateTime getOperationTime() { return operationTime; }
    public void setOperationTime(LocalDateTime operationTime) { this.operationTime = operationTime; }
    public String getCancelReason() { return cancelReason; }
    public void setCancelReason(String cancelReason) { this.cancelReason = cancelReason; }
}
