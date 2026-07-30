package dev.workflowguard.domain;

import java.util.Objects;
import java.util.UUID;

public record WorkflowDependency(
        UUID id,
        UUID sourceStepId,
        UUID targetStepId,
        DependencyType type,
        DependencyConfidence confidence,
        String reason
) {
    public WorkflowDependency {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(sourceStepId, "sourceStepId");
        Objects.requireNonNull(targetStepId, "targetStepId");
        if (sourceStepId.equals(targetStepId)) {
            throw new IllegalArgumentException("A dependency must connect two different steps");
        }
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(confidence, "confidence");
        Objects.requireNonNull(reason, "reason");
        if (reason.isBlank()) {
            throw new IllegalArgumentException("Dependency reason must not be blank");
        }
    }
}
