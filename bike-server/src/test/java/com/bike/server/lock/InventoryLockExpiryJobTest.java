package com.bike.server.lock;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.LocalDateTime;
import java.util.List;

class InventoryLockExpiryJobTest {

    @Test
    void expiresLocksAndWritesAuditRecordsInOneJobRun() {
        InventoryLockMapper mapper = Mockito.mock(InventoryLockMapper.class);
        InventoryLockView lock = new InventoryLockView();
        lock.setLockNo("LK-1");
        lock.setExpireTime(LocalDateTime.now().minusMinutes(1));
        Mockito.when(mapper.selectExpiredForAudit()).thenReturn(List.of(lock));
        Mockito.when(mapper.expireLocks()).thenReturn(1);

        new InventoryLockExpiryJob(mapper).expireLocks();

        Mockito.verify(mapper).expireLocks();
        Mockito.verify(mapper).insertOperationLog("LK-1", "库存锁定自动过期", null,
                "system", "到期时间:" + lock.getExpireTime(), null);
    }

    @Test
    void skipsUpdateWhenNoLockHasExpired() {
        InventoryLockMapper mapper = Mockito.mock(InventoryLockMapper.class);
        Mockito.when(mapper.selectExpiredForAudit()).thenReturn(List.of());

        new InventoryLockExpiryJob(mapper).expireLocks();

        Mockito.verify(mapper, Mockito.never()).expireLocks();
        Mockito.verify(mapper, Mockito.never()).insertOperationLog(Mockito.anyString(), Mockito.anyString(),
                Mockito.any(), Mockito.anyString(), Mockito.anyString(), Mockito.any());
    }
}
