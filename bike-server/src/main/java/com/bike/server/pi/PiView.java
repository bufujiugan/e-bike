package com.bike.server.pi;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonFormat;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public class PiView {
    @JsonFormat(shape = JsonFormat.Shape.STRING) private BigDecimal otherFeeAmount;
    public BigDecimal getOtherFeeAmount(){return otherFeeAmount;}
    public void setOtherFeeAmount(BigDecimal value){otherFeeAmount=value;}
    private String documentRenderVersion;
    public String getDocumentRenderVersion(){return documentRenderVersion;}
    public void setDocumentRenderVersion(String value){documentRenderVersion=value;}
    private Long seriesId;
    private Long previousRevisionId;
    private List<PiFulfillmentLine> fulfillment = List.of();
    private List<PiDispatchBatch> dispatchBatches = List.of();
    public List<PiDispatchBatch> getDispatchBatches() { return dispatchBatches; }
    public void setDispatchBatches(List<PiDispatchBatch> value) { dispatchBatches=List.copyOf(value); }
    public Long getSeriesId() { return seriesId; }
    public void setSeriesId(Long value) { seriesId=value; }
    public Long getPreviousRevisionId() { return previousRevisionId; }
    public void setPreviousRevisionId(Long value) { previousRevisionId=value; }
    public List<PiFulfillmentLine> getFulfillment() { return fulfillment; }
    public void setFulfillment(List<PiFulfillmentLine> value) { fulfillment=List.copyOf(value); }
    private String reservationStatus;
    public String getReservationStatus() { return reservationStatus; }
    public void setReservationStatus(String value) { reservationStatus = value; }
    private Long id;
    private String piNo;
    private int revision;
    private Long customerId;
    private String customerCode;
    private String customerName;
    private Long agentId;
    private String agentName;
    private Long sourceOrderId;
    private String tradeType;
    private String currencyCode;
    private String status;
    private String paymentStatus;
    private String releaseStatus;
    private LocalDate plannedShipDate;
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private BigDecimal subtotal;
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private BigDecimal discountAmount;
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private BigDecimal taxAmount;
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private BigDecimal freightAmount;
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private BigDecimal totalAmount;
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private BigDecimal depositRequiredAmount;
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private BigDecimal shipmentRequiredAmount;
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private BigDecimal paidAmount;
    private String remark;
    private LocalDateTime releasedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private PiDocumentSnapshot document;
    @JsonIgnore
    private String documentJson;
    private List<PiItemView> items = List.of();
    private List<PiPaymentView> payments = List.of();
    private List<PiLogisticsView> logistics = List.of();

    public Long getId() { return id; }
    public void setId(Long value) { id = value; }

    public String getPiNo() { return piNo; }
    public void setPiNo(String value) { piNo = value; }

    public int getRevision() { return revision; }
    public void setRevision(int value) { revision = value; }

    public Long getCustomerId() { return customerId; }
    public void setCustomerId(Long value) { customerId = value; }

    public String getCustomerCode() { return customerCode; }
    public void setCustomerCode(String value) { customerCode = value; }

    public String getCustomerName() { return customerName; }
    public void setCustomerName(String value) { customerName = value; }

    public Long getAgentId() { return agentId; }
    public void setAgentId(Long value) { agentId = value; }

    public String getAgentName() { return agentName; }
    public void setAgentName(String value) { agentName = value; }

    public Long getSourceOrderId() { return sourceOrderId; }
    public void setSourceOrderId(Long value) { sourceOrderId = value; }

    public String getTradeType() { return tradeType; }
    public void setTradeType(String value) { tradeType = value; }

    public String getCurrencyCode() { return currencyCode; }
    public void setCurrencyCode(String value) { currencyCode = value; }

    public String getStatus() { return status; }
    public void setStatus(String value) { status = value; }

    public String getPaymentStatus() { return paymentStatus; }
    public void setPaymentStatus(String value) { paymentStatus = value; }

    public String getReleaseStatus() { return releaseStatus; }
    public void setReleaseStatus(String value) { releaseStatus = value; }

    public LocalDate getPlannedShipDate() { return plannedShipDate; }
    public void setPlannedShipDate(LocalDate value) { plannedShipDate = value; }

    public BigDecimal getSubtotal() { return subtotal; }
    public void setSubtotal(BigDecimal value) { subtotal = value; }

    public BigDecimal getDiscountAmount() { return discountAmount; }
    public void setDiscountAmount(BigDecimal value) { discountAmount = value; }

    public BigDecimal getTaxAmount() { return taxAmount; }
    public void setTaxAmount(BigDecimal value) { taxAmount = value; }

    public BigDecimal getFreightAmount() { return freightAmount; }
    public void setFreightAmount(BigDecimal value) { freightAmount = value; }

    public BigDecimal getTotalAmount() { return totalAmount; }
    public void setTotalAmount(BigDecimal value) { totalAmount = value; }

    public BigDecimal getDepositRequiredAmount() { return depositRequiredAmount; }
    public void setDepositRequiredAmount(BigDecimal value) { depositRequiredAmount = value; }

    public BigDecimal getShipmentRequiredAmount() { return shipmentRequiredAmount; }
    public void setShipmentRequiredAmount(BigDecimal value) { shipmentRequiredAmount = value; }

    public BigDecimal getPaidAmount() { return paidAmount; }
    public void setPaidAmount(BigDecimal value) { paidAmount = value; }

    public String getRemark() { return remark; }
    public void setRemark(String value) { remark = value; }

    public LocalDateTime getReleasedAt() { return releasedAt; }
    public void setReleasedAt(LocalDateTime value) { releasedAt = value; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime value) { createdAt = value; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime value) { updatedAt = value; }

    public PiDocumentSnapshot getDocument() { return document; }
    public void setDocument(PiDocumentSnapshot value) { document = value; }

    public String getDocumentJson() { return documentJson; }
    public void setDocumentJson(String value) { documentJson = value; }

    public List<PiItemView> getItems() { return items; }
    public void setItems(List<PiItemView> value) { items = value == null ? List.of() : List.copyOf(value); }
    public List<PiPaymentView> getPayments() { return payments; }
    public void setPayments(List<PiPaymentView> value) { payments = value == null ? List.of() : List.copyOf(value); }
    public List<PiLogisticsView> getLogistics() { return logistics; }
    public void setLogistics(List<PiLogisticsView> value) { logistics = value == null ? List.of() : List.copyOf(value); }
}

