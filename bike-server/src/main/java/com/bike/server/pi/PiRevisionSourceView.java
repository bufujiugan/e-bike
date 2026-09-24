package com.bike.server.pi;

import java.time.LocalDateTime;

/** Saved source selection, including the original expiry; it never renews the source lock. */
public record PiRevisionSourceView(long lockId, long skuId, int quantity, String lockNo, String skuCode,
        String productName, LocalDateTime expiresAt, Long orderId) {}
