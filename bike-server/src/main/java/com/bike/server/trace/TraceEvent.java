package com.bike.server.trace;

import java.time.LocalDateTime;

public class TraceEvent {
    private String type; private String action; private String partType; private String partSn; private String operatorName; private String reason; private LocalDateTime operationTime;
    public String getType(){return type;} public void setType(String v){type=v;} public String getAction(){return action;} public void setAction(String v){action=v;} public String getPartType(){return partType;} public void setPartType(String v){partType=v;} public String getPartSn(){return partSn;} public void setPartSn(String v){partSn=v;} public String getOperatorName(){return operatorName;} public void setOperatorName(String v){operatorName=v;} public String getReason(){return reason;} public void setReason(String v){reason=v;} public LocalDateTime getOperationTime(){return operationTime;} public void setOperationTime(LocalDateTime v){operationTime=v;}
}
