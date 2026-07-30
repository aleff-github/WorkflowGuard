package dev.workflowguard.core;

import dev.workflowguard.domain.ExecutionPhase;
import dev.workflowguard.domain.ExecutionRun;
import dev.workflowguard.domain.ExecutionRunSummary;
import dev.workflowguard.domain.InvariantCheckResult;
import dev.workflowguard.domain.RunAssessment;
import dev.workflowguard.domain.RunStatus;
import dev.workflowguard.domain.StepExecutionResult;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public final class ExecutionRunSummarizer {
    public ExecutionRunSummary summarize(ExecutionRun run) {
        Objects.requireNonNull(run, "run");
        int passedChecks = (int) run.invariantResults().stream()
                .filter(InvariantCheckResult::passed)
                .count();
        int mutationViolations = failedChecks(run, ExecutionPhase.AFTER_PROBE);
        int cleanupViolations = failedChecks(
                run,
                ExecutionPhase.POST_CLEANUP_PROBE
        );
        boolean hasCleanupChecks = run.invariantResults().stream()
                .anyMatch(result ->
                        result.comparisonPhase() == ExecutionPhase.POST_CLEANUP_PROBE);

        return new ExecutionRunSummary(
                run.id(),
                run.mutationCaseName(),
                run.startedAt(),
                run.status(),
                actors(run),
                mutationStatuses(run),
                passedChecks,
                mutationViolations,
                cleanupViolations,
                assess(
                        run.status(),
                        run.invariantResults().isEmpty(),
                        mutationViolations,
                        cleanupViolations,
                        hasCleanupChecks
                ),
                run.duration()
        );
    }

    private int failedChecks(ExecutionRun run, ExecutionPhase phase) {
        return (int) run.invariantResults().stream()
                .filter(result -> result.comparisonPhase() == phase)
                .filter(result -> !result.passed())
                .count();
    }

    private String actors(ExecutionRun run) {
        Set<String> actors = run.stepResults().stream()
                .filter(result -> result.phase() == ExecutionPhase.MUTATION)
                .map(StepExecutionResult::actorName)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        return actors.isEmpty() ? "—" : String.join(", ", actors);
    }

    private String mutationStatuses(ExecutionRun run) {
        String sequence = run.stepResults().stream()
                .filter(result -> result.phase() == ExecutionPhase.MUTATION)
                .map(result -> result.statusCode()
                        .map(String::valueOf)
                        .orElseGet(() -> result.status().name()))
                .collect(Collectors.joining(" → "));
        return sequence.isEmpty() ? "—" : sequence;
    }

    private RunAssessment assess(
            RunStatus status,
            boolean noInvariantChecks,
            int mutationViolations,
            int cleanupViolations,
            boolean hasCleanupChecks
    ) {
        if (status == RunStatus.BLOCKED) {
            return RunAssessment.BLOCKED;
        }
        if (status == RunStatus.CANCELLED) {
            return RunAssessment.CANCELLED;
        }
        if (status == RunStatus.FAILED) {
            return RunAssessment.EXECUTION_FAILED;
        }
        if (cleanupViolations > 0) {
            return RunAssessment.CLEANUP_FAILED;
        }
        if (mutationViolations > 0) {
            return hasCleanupChecks
                    ? RunAssessment.MUTATION_VIOLATION_RESTORED
                    : RunAssessment.MUTATION_VIOLATION;
        }
        if (noInvariantChecks) {
            return RunAssessment.NOT_EVALUATED;
        }
        return RunAssessment.VERIFIED;
    }
}
