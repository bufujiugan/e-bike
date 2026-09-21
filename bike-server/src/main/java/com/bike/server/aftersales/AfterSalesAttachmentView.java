package com.bike.server.aftersales;

import java.time.LocalDateTime;

/** A public attachment never includes its storage object key. */
public record AfterSalesAttachmentView(Long id, Long caseId, Long messageId, String fileName,
    String contentType, long fileSize, String sha256, String visibility, String category,
    Long uploadedBy, LocalDateTime createdAt) {}
