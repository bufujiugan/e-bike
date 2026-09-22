package com.bike.server.aftersales;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AfterSalesShipmentRequest(@NotBlank(message="补发配件不能为空") @Size(max=255,message="配件名称长度不能超过255") String partName, @Min(value=1,message="补发数量必须大于0") int quantity, @Size(max=128,message="承运商长度不能超过128") String carrier, @Size(max=128,message="运单号长度不能超过128") String trackingNo, boolean oldPartReturnRequired, Long solutionItemId) {
    public AfterSalesShipmentRequest(String partName,int quantity,String carrier,String trackingNo,boolean returnRequired) { this(partName,quantity,carrier,trackingNo,returnRequired,null); }
}
