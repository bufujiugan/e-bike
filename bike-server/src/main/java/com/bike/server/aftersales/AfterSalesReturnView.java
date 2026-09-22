package com.bike.server.aftersales;

import java.time.LocalDateTime;

public record AfterSalesReturnView(long shipmentId, long caseId, String partName, int quantity, String status,
    long revision, String warehouseName, String recipient, String phone, String addressLine, String postalCode,
    String carrier, String trackingNo, LocalDateTime sentAt, LocalDateTime receivedAt, LocalDateTime inspectedAt,
    String inspectionResult, String dispositionNote, LocalDateTime completedAt, LocalDateTime updatedAt) {}
