package dev.workflowguard.domain;

import java.util.Objects;

public record PlannedStep(ExecutionPhase phase, WorkflowStep step) {
    public PlannedStep {
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(step, "step");
    }
}
