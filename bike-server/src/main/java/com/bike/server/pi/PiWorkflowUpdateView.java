package com.bike.server.pi;

import java.time.LocalDateTime;

public record PiWorkflowUpdateView(
        long id,
        String stepCode,
        PiWorkflowDetails details,
        String note,
        Long createdBy,
        LocalDateTime createdAt) {}
