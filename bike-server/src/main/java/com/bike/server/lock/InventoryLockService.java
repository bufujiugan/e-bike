package com.bike.server.lock;

import com.bike.server.common.PageResponse;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Instant;

public interface InventoryLockService {
    InventoryLockView getByNo(String lockNo);
    PageResponse<InventoryLockView> list(Long agentId,long page,long pageSize);
    InventoryLockView create(InventoryLockCreateRequest request);
    CustomerLockReceipt lockCustomerInventory(CustomerLockRequest request);
    InventoryLockView release(long id,String reason);
    InventoryLockView convert(long id,int quantity);
    PageResponse<LegacyConversionAuditView> legacyConversions(long page,long pageSize);

    record CustomerLockRequest(@NotBlank @Size(max = 64) String requestId, @Positive long modelId,
                               @Positive int quantity) {}
    record CustomerLockReceipt(String lockNo, Instant expireTime) {}
}
