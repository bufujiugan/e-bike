package com.bike.server.aftersales;
import java.time.LocalDateTime;
import java.util.List;
public record AfterSalesPlanView(long id,int revision,String status,AfterSalesSolutionRequest document,
    List<AfterSalesPlanItem> parts,LocalDateTime createdAt,LocalDateTime confirmedAt,LocalDateTime approvedAt) {}
