package com.bike.server.stock;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public record StockOutRequest(@NotNull(message = "客户不能为空") Long customerId,
                              @NotNull(message = "订单不能为空") Long orderId,
                              @NotEmpty(message = "至少扫描一辆整车") List<String> vehicleQrCodes,
                              @jakarta.validation.constraints.Size(max=400) String remark) {
    public StockOutRequest(Long customerId,Long orderId,List<String> vehicleQrCodes){this(customerId,orderId,vehicleQrCodes,null);}
}
