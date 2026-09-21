package com.bike.server.aftersales;

/** Internal read model; controllers must not serialize this record. */
public record AfterSalesStoredAttachment(Long id, Long caseId, Long messageId, String objectKey,
    String fileName, String contentType, long fileSize, String sha256, String visibility, String category) {}
