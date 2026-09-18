package com.bike.server.lock;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;

public record InventoryLockCreateRequest(Long agentId,
                                         @NotNull(message="车型不能为空") Long productModelId,
                                         @Min(value=1,message="锁定数量必须大于 0") int lockQty,
                                         LocalDateTime expireTime,
                                         String remark) {}
