package com.bike.server.aftersales;

import java.time.LocalDateTime;

public record AfterSalesNotification(long id,long caseId,long eventId,String eventType,String audience,
    String originalRecipient,String recipient,String messageKey,String subject,String body,String status,
    int attemptCount,int revision,LocalDateTime nextAttemptAt,String claimToken,LocalDateTime claimedUntil,
    String lastError,LocalDateTime sentAt,LocalDateTime createdAt,LocalDateTime updatedAt) {}
