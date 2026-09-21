package com.bike.server.aftersales;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AfterSalesDispatchRequest(
    @NotBlank(message="承运商不能为空") @Size(max=128,message="承运商长度不能超过128") String carrier,
    @NotBlank(message="运单号不能为空") @Size(max=128,message="运单号长度不能超过128") String trackingNo) {}
