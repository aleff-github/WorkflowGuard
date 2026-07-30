package dev.workflowguard.domain;

import dev.workflowguard.core.InvariantViolation;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record ExecutionRun(
        UUID id,
        UUID mutationCaseId,
        String mutationCaseName,
        RunStatus status,
        Instant startedAt,
        Instant finishedAt,
        List<StepExecutionResult> stepResults,
        Map<String, String> variables,
        Map<String, Map<String, String>> actorCookies,
        List<InvariantCheckResult> invariantResults,
        List<String> messages
) {
    public ExecutionRun {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(mutationCaseId, "mutationCaseId");
        Objects.requireNonNull(mutationCaseName, "mutationCaseName");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(startedAt, "startedAt");
        Objects.requireNonNull(finishedAt, "finishedAt");
        stepResults = List.copyOf(Objects.requireNonNull(stepResults, "stepResults"));
        variables = Map.copyOf(Objects.requireNonNull(variables, "variables"));
        Objects.requireNonNull(actorCookies, "actorCookies");
        actorCookies = actorCookies.entrySet().stream().collect(
                java.util.stream.Collectors.toUnmodifiableMap(
                        Map.Entry::getKey,
                        entry -> Map.copyOf(entry.getValue())
                )
        );
        invariantResults = List.copyOf(
                Objects.requireNonNull(invariantResults, "invariantResults")
        );
        messages = List.copyOf(Objects.requireNonNull(messages, "messages"));
    }

    public Duration duration() {
        return Duration.between(startedAt, finishedAt);
    }

    /**
     * Retains matrix and invariant outcomes while discarding sensitive, potentially
     * large HTTP evidence from an older run.
     */
    public ExecutionRun withoutHttpEvidence() {
        List<StepExecutionResult> compactSteps = stepResults.stream()
                .map(result -> new StepExecutionResult(
                        result.stepId(),
                        result.stepName(),
                        result.method(),
                        result.url(),
                        result.actorId(),
                        result.actorName(),
                        result.phase(),
                        result.startedAt(),
                        result.duration(),
                        result.status(),
                        result.statusCode(),
                        "",
                        "",
                        "",
                        Map.of(),
                        result.errorMessage()
                ))
                .toList();
        List<InvariantCheckResult> compactInvariants = invariantResults.stream()
                .map(result -> new InvariantCheckResult(
                        result.invariantId(),
                        result.invariantName(),
                        result.probeStepId(),
                        result.comparisonPhase(),
                        result.violations().stream()
                                .map(violation -> new InvariantViolation(
                                        violation.invariantName(),
                                        violation.jsonPointer(),
                                        violation.message(),
                                        null,
                                        null
                                ))
                                .toList(),
                        result.errorMessage()
                ))
                .toList();
        Map<String, Map<String, String>> compactActors = actorCookies.keySet().stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        actor -> actor,
                        ignored -> Map.of()
                ));
        List<String> compactMessages = new ArrayList<>(messages);
        if (!compactMessages.contains(
                "Full HTTP evidence was discarded when a newer run completed."
        )) {
            compactMessages.add(
                    "Full HTTP evidence was discarded when a newer run completed."
            );
        }
        return new ExecutionRun(
                id,
                mutationCaseId,
                mutationCaseName,
                status,
                startedAt,
                finishedAt,
                compactSteps,
                Map.of(),
                compactActors,
                compactInvariants,
                compactMessages
        );
    }
}
