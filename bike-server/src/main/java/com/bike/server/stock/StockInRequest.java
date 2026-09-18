package com.bike.server.stock;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record StockInRequest(@NotBlank(message = "整车二维码不能为空") String vehicleQrCode,
                             @NotNull(message = "仓库不能为空") Long warehouseId,
                             @NotNull(message = "库位不能为空") Long locationId) {
}
