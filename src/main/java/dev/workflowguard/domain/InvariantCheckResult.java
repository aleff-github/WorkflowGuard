package dev.workflowguard.domain;

import dev.workflowguard.core.InvariantViolation;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record InvariantCheckResult(
        UUID invariantId,
        String invariantName,
        UUID probeStepId,
        ExecutionPhase comparisonPhase,
        List<InvariantViolation> violations,
        Optional<String> errorMessage
) {
    public InvariantCheckResult {
        Objects.requireNonNull(invariantId, "invariantId");
        Objects.requireNonNull(invariantName, "invariantName");
        Objects.requireNonNull(probeStepId, "probeStepId");
        Objects.requireNonNull(comparisonPhase, "comparisonPhase");
        violations = List.copyOf(Objects.requireNonNull(violations, "violations"));
        errorMessage = Objects.requireNonNull(errorMessage, "errorMessage");
    }

    public boolean passed() {
        return violations.isEmpty() && errorMessage.isEmpty();
    }
}
