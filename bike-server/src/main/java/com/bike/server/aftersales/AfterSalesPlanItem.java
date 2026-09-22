package com.bike.server.aftersales;
import java.math.BigDecimal;
public record AfterSalesPlanItem(long id,long versionId,String partCode,String partName,int quantity,
    BigDecimal unitCharge,boolean returnRequired,long allocatedQty) {}
