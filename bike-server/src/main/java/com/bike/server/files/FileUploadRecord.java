package com.bike.server.files;

import java.time.LocalDateTime;

public record FileUploadRecord(String objectKey, String purpose, String businessId, String fileName,
        String contentType, long fileSize, String sha256, String state, String objectState, long version,
        Long createdBy, String createdName, LocalDateTime createdAt, LocalDateTime updatedAt,
        LocalDateTime checkedAt, String errorCode, String reviewReason, Long reviewedBy) {}
