package com.bike.server.master;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LocationUpdateRequest(
        @NotBlank(message = "库位编码不能为空") @Size(max = 64, message = "库位编码长度不能超过 64") String code,
        @NotBlank(message = "库位名称不能为空") @Size(max = 150, message = "库位名称长度不能超过 150") String name) {
}
