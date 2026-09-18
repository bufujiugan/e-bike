package com.bike.server.lock;

import java.time.LocalDateTime;

public class InventoryLockView {
    private Long id; private String lockNo; private Long agentId; private String agentName; private Long productModelId; private String modelName;
    private int lockQty; private int releasedQty; private int convertedQty; private String status; private LocalDateTime expireTime; private LocalDateTime createdAt; private String remark;
    public Long getId(){return id;} public void setId(Long v){id=v;} public String getLockNo(){return lockNo;} public void setLockNo(String v){lockNo=v;} public Long getAgentId(){return agentId;} public void setAgentId(Long v){agentId=v;} public String getAgentName(){return agentName;} public void setAgentName(String v){agentName=v;} public Long getProductModelId(){return productModelId;} public void setProductModelId(Long v){productModelId=v;} public String getModelName(){return modelName;} public void setModelName(String v){modelName=v;} public int getLockQty(){return lockQty;} public void setLockQty(int v){lockQty=v;} public int getReleasedQty(){return releasedQty;} public void setReleasedQty(int v){releasedQty=v;} public int getConvertedQty(){return convertedQty;} public void setConvertedQty(int v){convertedQty=v;} public String getStatus(){return status;} public void setStatus(String v){status=v;} public LocalDateTime getExpireTime(){return expireTime;} public void setExpireTime(LocalDateTime v){expireTime=v;} public LocalDateTime getCreatedAt(){return createdAt;} public void setCreatedAt(LocalDateTime v){createdAt=v;} public String getRemark(){return remark;} public void setRemark(String v){remark=v;}
}
