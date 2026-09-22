package com.bike.server.aftersales;

import java.time.LocalDateTime;

public class AfterSalesShipmentView {
    private int revision; public int getRevision(){return revision;} public void setRevision(int value){revision=value;}
    private String warehouseName; public String getWarehouseName(){return warehouseName;} public void setWarehouseName(String value){warehouseName=value;}
    private String recipient; public String getRecipient(){return recipient;} public void setRecipient(String value){recipient=value;}
    private String phone; public String getPhone(){return phone;} public void setPhone(String value){phone=value;}
    private String countryCode; public String getCountryCode(){return countryCode;} public void setCountryCode(String value){countryCode=value;}
    private String region; public String getRegion(){return region;} public void setRegion(String value){region=value;}
    private String city; public String getCity(){return city;} public void setCity(String value){city=value;}
    private String addressLine; public String getAddressLine(){return addressLine;} public void setAddressLine(String value){addressLine=value;}
    private String postalCode; public String getPostalCode(){return postalCode;} public void setPostalCode(String value){postalCode=value;}
    private java.time.LocalDate expectedArrivalDate; public java.time.LocalDate getExpectedArrivalDate(){return expectedArrivalDate;} public void setExpectedArrivalDate(java.time.LocalDate value){expectedArrivalDate=value;}
    private String logisticsStatus; public String getLogisticsStatus(){return logisticsStatus;} public void setLogisticsStatus(String value){logisticsStatus=value;}
    private String logisticsNote; public String getLogisticsNote(){return logisticsNote;} public void setLogisticsNote(String value){logisticsNote=value;}
    private LocalDateTime deliveredAt; public LocalDateTime getDeliveredAt(){return deliveredAt;} public void setDeliveredAt(LocalDateTime value){deliveredAt=value;}
    private String cancelledReason; public String getCancelledReason(){return cancelledReason;} public void setCancelledReason(String value){cancelledReason=value;}
    private String partCode; public String getPartCode(){return partCode;} public void setPartCode(String value){partCode=value;}
    private Long solutionItemId; private String approvalSource;
    public Long getSolutionItemId(){return solutionItemId;} public void setSolutionItemId(Long value){solutionItemId=value;}
    public String getApprovalSource(){return approvalSource;} public void setApprovalSource(String value){approvalSource=value;}
    private Long id; private Long caseId; private String partName; private int quantity; private String carrier; private String trackingNo; private String status; private LocalDateTime shippedAt; private boolean oldPartReturnRequired; private LocalDateTime oldPartReturnReceivedAt; private Long createdBy; private LocalDateTime createdAt; private LocalDateTime updatedAt;
    public Long getId(){return id;} public void setId(Long v){id=v;} public Long getCaseId(){return caseId;} public void setCaseId(Long v){caseId=v;} public String getPartName(){return partName;} public void setPartName(String v){partName=v;} public int getQuantity(){return quantity;} public void setQuantity(int v){quantity=v;} public String getCarrier(){return carrier;} public void setCarrier(String v){carrier=v;} public String getTrackingNo(){return trackingNo;} public void setTrackingNo(String v){trackingNo=v;} public String getStatus(){return status;} public void setStatus(String v){status=v;} public LocalDateTime getShippedAt(){return shippedAt;} public void setShippedAt(LocalDateTime v){shippedAt=v;} public boolean isOldPartReturnRequired(){return oldPartReturnRequired;} public void setOldPartReturnRequired(boolean v){oldPartReturnRequired=v;} public LocalDateTime getOldPartReturnReceivedAt(){return oldPartReturnReceivedAt;} public void setOldPartReturnReceivedAt(LocalDateTime v){oldPartReturnReceivedAt=v;} public Long getCreatedBy(){return createdBy;} public void setCreatedBy(Long v){createdBy=v;} public LocalDateTime getCreatedAt(){return createdAt;} public void setCreatedAt(LocalDateTime v){createdAt=v;} public LocalDateTime getUpdatedAt(){return updatedAt;} public void setUpdatedAt(LocalDateTime v){updatedAt=v;}
}
