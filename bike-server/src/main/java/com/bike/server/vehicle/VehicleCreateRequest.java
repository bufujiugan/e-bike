package com.bike.server.vehicle;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record VehicleCreateRequest(
        @NotBlank(message = "车架 SN 不能为空") @Size(max = 128, message = "车架 SN 长度不能超过 128") String frameSn,
        Long modelId) {
}
