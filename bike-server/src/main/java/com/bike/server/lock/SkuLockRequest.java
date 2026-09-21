package com.bike.server.lock;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.LocalDateTime;
import java.util.List;

public record SkuLockRequest(@NotBlank @Size(max=64) String requestId,
        @NotNull @Positive Long customerId, @Positive Long agentId,
        LocalDateTime expiresAt, @Size(max=500) String remark,
        @NotEmpty @Size(max=100) List<@NotNull @Valid Item> items) {
    public record Item(@Positive long skuId, @Min(1) @Max(1000000) int quantity) {}
}
