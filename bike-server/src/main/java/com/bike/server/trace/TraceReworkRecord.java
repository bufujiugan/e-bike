package com.bike.server.trace;

import java.time.LocalDateTime;

public class TraceReworkRecord {
    private String reworkNo;
    private String partType;
    private String oldPartSn;
    private String newPartSn;
    private String reason;
    private String status;
    private String operatorName;
    private LocalDateTime operationTime;

    public String getReworkNo() { return reworkNo; }
    public void setReworkNo(String reworkNo) { this.reworkNo = reworkNo; }
    public String getPartType() { return partType; }
    public void setPartType(String partType) { this.partType = partType; }
    public String getOldPartSn() { return oldPartSn; }
    public void setOldPartSn(String oldPartSn) { this.oldPartSn = oldPartSn; }
    public String getNewPartSn() { return newPartSn; }
    public void setNewPartSn(String newPartSn) { this.newPartSn = newPartSn; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getOperatorName() { return operatorName; }
    public void setOperatorName(String operatorName) { this.operatorName = operatorName; }
    public LocalDateTime getOperationTime() { return operationTime; }
    public void setOperationTime(LocalDateTime operationTime) { this.operationTime = operationTime; }
}
