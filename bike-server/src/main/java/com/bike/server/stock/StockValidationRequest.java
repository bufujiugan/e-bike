package com.bike.server.stock;

import jakarta.validation.constraints.NotBlank;

public record StockValidationRequest(@NotBlank(message="整车二维码不能为空") String vehicleQrCode,
                                     Long customerId,
                                     Long orderId) {
    /** 保留入库校验的简化构造；出库校验必须额外提供客户和订单上下文。 */
    public StockValidationRequest(String vehicleQrCode) {
        this(vehicleQrCode, null, null);
    }
}
