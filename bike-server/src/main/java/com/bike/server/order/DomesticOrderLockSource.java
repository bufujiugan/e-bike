package com.bike.server.order;

import java.time.LocalDateTime;

public record DomesticOrderLockSource(long lockId, String lockNo, long skuId, int quantity,
        LocalDateTime expiresAt, LocalDateTime renewedExpiresAt) {
    public DomesticOrderLockSource(long lockId, String lockNo, long skuId, int quantity, LocalDateTime expiresAt) {
        this(lockId, lockNo, skuId, quantity, expiresAt, null);
    }
}
