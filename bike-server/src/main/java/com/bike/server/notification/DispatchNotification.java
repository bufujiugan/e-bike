package com.bike.server.notification;

import java.time.LocalDateTime;

public record DispatchNotification(long id, Kind kind, long documentId, String documentNo,
                                   String batchNo, int quantity, LocalDateTime dispatchedAt,
                                   LocalDateTime readAt) {
    public enum Kind { ORDER, PI }
}
