package dev.workflowguard.application;

import dev.workflowguard.domain.Workflow;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record WorkflowSnapshot(
        List<Workflow> workflows,
        Optional<UUID> activeWorkflowId
) {
    public WorkflowSnapshot {
        workflows = List.copyOf(Objects.requireNonNull(workflows, "workflows"));
        Objects.requireNonNull(activeWorkflowId, "activeWorkflowId");
    }
}
