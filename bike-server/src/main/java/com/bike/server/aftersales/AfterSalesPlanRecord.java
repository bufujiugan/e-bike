package com.bike.server.aftersales;
import java.time.LocalDateTime;
public record AfterSalesPlanRecord(long id,long caseId,int revision,String status,String documentJson,
    Long createdBy,LocalDateTime createdAt,Long confirmedBy,LocalDateTime confirmedAt,Long approvedBy,LocalDateTime approvedAt) {}
