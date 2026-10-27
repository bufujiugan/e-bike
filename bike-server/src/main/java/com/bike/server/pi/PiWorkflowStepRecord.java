package com.bike.server.pi;

import java.time.LocalDateTime;

public record PiWorkflowStepRecord(
        int order,
        String code,
        String name,
        String status,
        Long completedBy,
        LocalDateTime completedAt,
        String note,
        String detailJson) {}
