package com.bike.server.agent;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;

public record PurchaseIntentCreateRequest(Long agentId,
                                          @NotNull Long productModelId,
                                          @Min(value = 1, message = "意向数量必须大于 0") int intentQty,
                                          LocalDateTime expireTime,
                                          String remark) {
}
