package com.bike.server.order;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.util.List;

public record DomesticSkuOrderRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotNull @Positive Long customerId,
        @Positive Long agentId,
        LocalDate plannedShipDate,
        @Size(max = 500) String remark,
        String transportArrangement,
        @Size(max = 500) String transportRemark,
        @Size(max = 100) List<@NotNull @Valid Item> items,
        @Size(max = 100) List<@NotNull @Valid Source> sources) {
    public record Item(@Positive long skuId, @Min(1) @Max(1000000) int quantity) {}
    public record Source(@Positive long lockId, @Min(1) @Max(1000000) int quantity) {}
}
