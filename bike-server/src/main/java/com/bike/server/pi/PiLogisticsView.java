package com.bike.server.pi;

import java.time.LocalDateTime;

public class PiLogisticsView {
    private Long id,piId,createdBy,stockOutBatchId;
    private String batchNo,carrier,trackingNo,status,remark,linkType,revokeReason;
    private LocalDateTime shippedAt,createdAt,revokedAt;
    private Integer revision,shippedQtySnapshot;
    public Long getId(){return id;} public void setId(Long v){id=v;}
    public Long getPiId(){return piId;} public void setPiId(Long v){piId=v;}
    public Long getCreatedBy(){return createdBy;} public void setCreatedBy(Long v){createdBy=v;}
    public Long getStockOutBatchId(){return stockOutBatchId;} public void setStockOutBatchId(Long v){stockOutBatchId=v;}
    public String getBatchNo(){return batchNo;} public void setBatchNo(String v){batchNo=v;}
    public String getCarrier(){return carrier;} public void setCarrier(String v){carrier=v;}
    public String getTrackingNo(){return trackingNo;} public void setTrackingNo(String v){trackingNo=v;}
    public String getStatus(){return status;} public void setStatus(String v){status=v;}
    public String getRemark(){return remark;} public void setRemark(String v){remark=v;}
    public String getLinkType(){return linkType;} public void setLinkType(String v){linkType=v;}
    public String getRevokeReason(){return revokeReason;} public void setRevokeReason(String v){revokeReason=v;}
    public LocalDateTime getShippedAt(){return shippedAt;} public void setShippedAt(LocalDateTime v){shippedAt=v;}
    public LocalDateTime getCreatedAt(){return createdAt;} public void setCreatedAt(LocalDateTime v){createdAt=v;}
    public LocalDateTime getRevokedAt(){return revokedAt;} public void setRevokedAt(LocalDateTime v){revokedAt=v;}
    public Integer getRevision(){return revision;} public void setRevision(Integer v){revision=v;}
    public Integer getShippedQtySnapshot(){return shippedQtySnapshot;} public void setShippedQtySnapshot(Integer v){shippedQtySnapshot=v;}
}
