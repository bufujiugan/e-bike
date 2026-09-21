package com.bike.server.lock;

import com.bike.server.common.PageResponse;

public interface InventoryLockService {
    InventoryLockView getByNo(String lockNo);
    PageResponse<InventoryLockView> list(Long agentId,long page,long pageSize);
    InventoryLockView create(InventoryLockCreateRequest request);
    InventoryLockView release(long id,String reason);
    InventoryLockView convert(long id,int quantity);
    PageResponse<LegacyConversionAuditView> legacyConversions(long page,long pageSize);
}
