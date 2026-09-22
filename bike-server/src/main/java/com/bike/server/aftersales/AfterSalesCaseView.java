package com.bike.server.aftersales;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public class AfterSalesCaseView {
    private AfterSalesPlanView solutionVersion;
    public AfterSalesPlanView getSolutionVersion(){return solutionVersion;}
    public void setSolutionVersion(AfterSalesPlanView value){solutionVersion=value;}
    private Long id; private String caseNo; private Long customerId; private String customerName; private Long agentId; private String agentName;
    private Long vehicleId; private String frameSn; private Long productModelId; private String modelName; private String motorSn; private String batterySn; private String displaySn;
    private LocalDate purchaseDate; private LocalDateTime failureTime; private String faultType; private String faultPart; private String errorCode; private String description; private String attemptedRepair;
    private String warrantyStatus; private String status; private Long assigneeId; private String technicalJudgment; private String solution; private String internalNote; private String rejectionReason;
    private Long createdBy; private Long updatedBy; private LocalDateTime submittedAt; private LocalDateTime closedAt; private LocalDateTime createdAt; private LocalDateTime updatedAt;
    private List<AfterSalesMessageView> messages = List.of(); private List<AfterSalesShipmentView> shipments = List.of();
    private List<AfterSalesHistoryView> history = List.of();
    private String companyName; private String contactName; private String contactPhone; private String contactEmail; private String countryCode;
    private String region; private String city; private String addressLine; private String postalCode; private String snapshotSource; private String assigneeName;
    private LocalDate endCustomerPurchaseDate;
    public String getCompanyName(){return companyName;} public void setCompanyName(String v){companyName=v;}
    public String getContactName(){return contactName;} public void setContactName(String v){contactName=v;}
    public String getContactPhone(){return contactPhone;} public void setContactPhone(String v){contactPhone=v;}
    public String getContactEmail(){return contactEmail;} public void setContactEmail(String v){contactEmail=v;}
    public String getCountryCode(){return countryCode;} public void setCountryCode(String v){countryCode=v;}
    public String getRegion(){return region;} public void setRegion(String v){region=v;}
    public String getCity(){return city;} public void setCity(String v){city=v;}
    public String getAddressLine(){return addressLine;} public void setAddressLine(String v){addressLine=v;}
    public String getPostalCode(){return postalCode;} public void setPostalCode(String v){postalCode=v;}
    public String getSnapshotSource(){return snapshotSource;} public void setSnapshotSource(String v){snapshotSource=v;}
    public String getAssigneeName(){return assigneeName;} public void setAssigneeName(String v){assigneeName=v;}
    public LocalDate getEndCustomerPurchaseDate(){return endCustomerPurchaseDate;} public void setEndCustomerPurchaseDate(LocalDate v){endCustomerPurchaseDate=v;}
    public Long getId(){return id;} public void setId(Long v){id=v;} public String getCaseNo(){return caseNo;} public void setCaseNo(String v){caseNo=v;}
    public Long getCustomerId(){return customerId;} public void setCustomerId(Long v){customerId=v;} public String getCustomerName(){return customerName;} public void setCustomerName(String v){customerName=v;}
    public Long getAgentId(){return agentId;} public void setAgentId(Long v){agentId=v;} public String getAgentName(){return agentName;} public void setAgentName(String v){agentName=v;}
    public Long getVehicleId(){return vehicleId;} public void setVehicleId(Long v){vehicleId=v;} public String getFrameSn(){return frameSn;} public void setFrameSn(String v){frameSn=v;}
    public Long getProductModelId(){return productModelId;} public void setProductModelId(Long v){productModelId=v;} public String getModelName(){return modelName;} public void setModelName(String v){modelName=v;}
    public String getMotorSn(){return motorSn;} public void setMotorSn(String v){motorSn=v;} public String getBatterySn(){return batterySn;} public void setBatterySn(String v){batterySn=v;} public String getDisplaySn(){return displaySn;} public void setDisplaySn(String v){displaySn=v;}
    public LocalDate getPurchaseDate(){return purchaseDate;} public void setPurchaseDate(LocalDate v){purchaseDate=v;} public LocalDateTime getFailureTime(){return failureTime;} public void setFailureTime(LocalDateTime v){failureTime=v;}
    public String getFaultType(){return faultType;} public void setFaultType(String v){faultType=v;} public String getFaultPart(){return faultPart;} public void setFaultPart(String v){faultPart=v;} public String getErrorCode(){return errorCode;} public void setErrorCode(String v){errorCode=v;}
    public String getDescription(){return description;} public void setDescription(String v){description=v;} public String getAttemptedRepair(){return attemptedRepair;} public void setAttemptedRepair(String v){attemptedRepair=v;}
    public String getWarrantyStatus(){return warrantyStatus;} public void setWarrantyStatus(String v){warrantyStatus=v;} public String getStatus(){return status;} public void setStatus(String v){status=v;}
    public Long getAssigneeId(){return assigneeId;} public void setAssigneeId(Long v){assigneeId=v;} public String getTechnicalJudgment(){return technicalJudgment;} public void setTechnicalJudgment(String v){technicalJudgment=v;} public String getSolution(){return solution;} public void setSolution(String v){solution=v;} public String getInternalNote(){return internalNote;} public void setInternalNote(String v){internalNote=v;} public String getRejectionReason(){return rejectionReason;} public void setRejectionReason(String v){rejectionReason=v;}
    public Long getCreatedBy(){return createdBy;} public void setCreatedBy(Long v){createdBy=v;} public Long getUpdatedBy(){return updatedBy;} public void setUpdatedBy(Long v){updatedBy=v;} public LocalDateTime getSubmittedAt(){return submittedAt;} public void setSubmittedAt(LocalDateTime v){submittedAt=v;} public LocalDateTime getClosedAt(){return closedAt;} public void setClosedAt(LocalDateTime v){closedAt=v;} public LocalDateTime getCreatedAt(){return createdAt;} public void setCreatedAt(LocalDateTime v){createdAt=v;} public LocalDateTime getUpdatedAt(){return updatedAt;} public void setUpdatedAt(LocalDateTime v){updatedAt=v;}
    public List<AfterSalesMessageView> getMessages(){return messages;} public void setMessages(List<AfterSalesMessageView> v){messages=v==null?List.of():List.copyOf(v);} public List<AfterSalesShipmentView> getShipments(){return shipments;} public void setShipments(List<AfterSalesShipmentView> v){shipments=v==null?List.of():List.copyOf(v);}
    public List<AfterSalesHistoryView> getHistory(){return history;} public void setHistory(List<AfterSalesHistoryView> v){history=v==null?List.of():List.copyOf(v);}
}
