package com.bike.server.assembly;

public class AssemblyPartRecord {
    private Long id;
    private String partSn;
    private String partType;
    private String status;
    private String bindStatus;
    private String currentFrameSn;
    private String qcStatus;
    public String getQcStatus() { return qcStatus; }
    public void setQcStatus(String qcStatus) { this.qcStatus = qcStatus; }
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getPartSn() { return partSn; }
    public void setPartSn(String partSn) { this.partSn = partSn; }
    public String getPartType() { return partType; }
    public void setPartType(String partType) { this.partType = partType; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getBindStatus() { return bindStatus; }
    public void setBindStatus(String bindStatus) { this.bindStatus = bindStatus; }
    public String getCurrentFrameSn() { return currentFrameSn; }
    public void setCurrentFrameSn(String currentFrameSn) { this.currentFrameSn = currentFrameSn; }
}
