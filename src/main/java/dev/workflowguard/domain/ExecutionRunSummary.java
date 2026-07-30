package dev.workflowguard.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record ExecutionRunSummary(
        UUID runId,
        String mutationCaseName,
        Instant startedAt,
        RunStatus runStatus,
        String actors,
        String mutationHttpStatuses,
        int passedInvariantChecks,
        int mutationViolations,
        int cleanupViolations,
        RunAssessment assessment,
        Duration duration
) {
    public ExecutionRunSummary {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(mutationCaseName, "mutationCaseName");
        Objects.requireNonNull(startedAt, "startedAt");
        Objects.requireNonNull(runStatus, "runStatus");
        Objects.requireNonNull(actors, "actors");
        Objects.requireNonNull(mutationHttpStatuses, "mutationHttpStatuses");
        if (passedInvariantChecks < 0 || mutationViolations < 0 || cleanupViolations < 0) {
            throw new IllegalArgumentException("Invariant counts must not be negative");
        }
        Objects.requireNonNull(assessment, "assessment");
        Objects.requireNonNull(duration, "duration");
    }
}
