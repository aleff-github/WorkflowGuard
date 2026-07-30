package dev.workflowguard.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record StepExecutionResult(
        UUID stepId,
        String stepName,
        String method,
        String url,
        UUID actorId,
        String actorName,
        ExecutionPhase phase,
        Instant startedAt,
        Duration duration,
        StepExecutionStatus status,
        Optional<Integer> statusCode,
        String rawRequest,
        String rawResponse,
        String responseBody,
        java.util.Map<String, String> extractedVariables,
        Optional<String> errorMessage
) {
    public StepExecutionResult {
        Objects.requireNonNull(stepId, "stepId");
        Objects.requireNonNull(stepName, "stepName");
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(url, "url");
        Objects.requireNonNull(actorId, "actorId");
        Objects.requireNonNull(actorName, "actorName");
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(startedAt, "startedAt");
        Objects.requireNonNull(duration, "duration");
        Objects.requireNonNull(status, "status");
        statusCode = Objects.requireNonNull(statusCode, "statusCode");
        Objects.requireNonNull(rawRequest, "rawRequest");
        Objects.requireNonNull(rawResponse, "rawResponse");
        Objects.requireNonNull(responseBody, "responseBody");
        extractedVariables = java.util.Map.copyOf(
                Objects.requireNonNull(extractedVariables, "extractedVariables")
        );
        errorMessage = Objects.requireNonNull(errorMessage, "errorMessage");
    }
}
