package dev.workflowguard.ports;

import dev.workflowguard.domain.ExecutionRun;

@FunctionalInterface
public interface AuditIssuePublisher {
    int publish(ExecutionRun run);

    static AuditIssuePublisher disabled() {
        return ignored -> 0;
    }
}
