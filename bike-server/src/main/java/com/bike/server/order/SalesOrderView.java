package com.bike.server.order;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public class SalesOrderView {
    private Long id;
    private String reservationStatus;
    private String reservationHoldMode;
    private LocalDateTime reservationHeldAt;
    public String getReservationStatus() { return reservationStatus; }
    public void setReservationStatus(String value) { reservationStatus = value; }
    public String getReservationHoldMode() { return reservationHoldMode; }
    public void setReservationHoldMode(String value) { reservationHoldMode = value; }
    public LocalDateTime getReservationHeldAt() { return reservationHeldAt; }
    public void setReservationHeldAt(LocalDateTime value) { reservationHeldAt = value; }
    private String sourceLockNo;
    private LocalDateTime reservationExpiresAt;
    private Integer reservedQty;
    public String getSourceLockNo(){return sourceLockNo;} public void setSourceLockNo(String v){sourceLockNo=v;}
    public LocalDateTime getReservationExpiresAt(){return reservationExpiresAt;} public void setReservationExpiresAt(LocalDateTime v){reservationExpiresAt=v;}
    public Integer getReservedQty(){return reservedQty;} public void setReservedQty(Integer v){reservedQty=v;}
    private String orderNo;
    private Long customerId;
    private String customerName;
    private Long agentId;
    private String agentName;
    private Long modelId;
    private String modelName;
    private int orderQty;
    private int shippedQty;
    private String status;
    private LocalDate plannedShipDate;
    private String remark;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private List<OrderVehicleView> shippedVehicles = List.of();
    public Long getId(){return id;} public void setId(Long v){id=v;}
    public String getOrderNo(){return orderNo;} public void setOrderNo(String v){orderNo=v;}
    public Long getCustomerId(){return customerId;} public void setCustomerId(Long v){customerId=v;}
    public String getCustomerName(){return customerName;} public void setCustomerName(String v){customerName=v;}
    public Long getAgentId(){return agentId;} public void setAgentId(Long v){agentId=v;}
    public String getAgentName(){return agentName;} public void setAgentName(String v){agentName=v;}
    public Long getModelId(){return modelId;} public void setModelId(Long v){modelId=v;}
    public String getModelName(){return modelName;} public void setModelName(String v){modelName=v;}
    public int getOrderQty(){return orderQty;} public void setOrderQty(int v){orderQty=v;}
    public int getShippedQty(){return shippedQty;} public void setShippedQty(int v){shippedQty=v;}
    public String getStatus(){return status;} public void setStatus(String v){status=v;}
    public LocalDate getPlannedShipDate(){return plannedShipDate;} public void setPlannedShipDate(LocalDate v){plannedShipDate=v;}
    public String getRemark(){return remark;} public void setRemark(String v){remark=v;}
    public LocalDateTime getCreatedAt(){return createdAt;} public void setCreatedAt(LocalDateTime v){createdAt=v;}
    public LocalDateTime getUpdatedAt(){return updatedAt;} public void setUpdatedAt(LocalDateTime v){updatedAt=v;}
    public List<OrderVehicleView> getShippedVehicles(){return shippedVehicles;}
    public void setShippedVehicles(List<OrderVehicleView> v){shippedVehicles=v == null ? List.of() : List.copyOf(v);}
}
