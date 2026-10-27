package com.bike.server.pi;

import java.util.List;

public record PiWorkflowView(
        String currentStage,
        String currentStep,
        int completedCount,
        int totalCount,
        String nextStep,
        List<PiWorkflowStepView> steps,
        List<PiWorkflowUpdateView> updates) {
    public PiWorkflowView {
        steps = List.copyOf(steps);
        updates = List.copyOf(updates);
    }
}
