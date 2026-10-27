package com.bike.server.pi;

import java.time.LocalDateTime;

public record PiWorkflowUpdateRecord(
        long id,
        String stepCode,
        String detailJson,
        String note,
        Long createdBy,
        LocalDateTime createdAt) {}
