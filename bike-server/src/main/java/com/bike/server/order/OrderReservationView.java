package com.bike.server.order;

import java.time.LocalDateTime;

public record OrderReservationView(
        Long orderId, String orderNo, String orderStatus, String holdMode, String reservationStatus,
        LocalDateTime expiresAt, int reservedQty, int shippedQty, Long paymentHoldPiId,
        LocalDateTime paymentHeldAt, LocalDateTime releasedAt) {
    public int remainingQuantity() { return Math.max(0, reservedQty - shippedQty); }
}
