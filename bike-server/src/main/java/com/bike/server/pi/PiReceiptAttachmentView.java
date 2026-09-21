package com.bike.server.pi;

import java.time.LocalDateTime;

/** Financial metadata only; storage object keys never leave the service. */
public record PiReceiptAttachmentView(String id, Long paymentId, String fileName, String contentType,
        long fileSize, String sha256, Long createdBy, LocalDateTime createdAt) {}
