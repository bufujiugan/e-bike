package com.bike.server.pi;

import java.time.LocalDateTime;

public record PiWorkflowStepView(
        int order,
        String code,
        String name,
        String status,
        Long completedBy,
        LocalDateTime completedAt,
        String note,
        PiWorkflowDetails details) {
    public PiWorkflowStepView(int order, String code, String name, String status, Long completedBy,
            LocalDateTime completedAt, String note) {
        this(order, code, name, status, completedBy, completedAt, note, null);
    }
}
