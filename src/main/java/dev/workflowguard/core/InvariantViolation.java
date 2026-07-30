package dev.workflowguard.core;

import java.util.Objects;

public record InvariantViolation(
        String invariantName,
        String jsonPointer,
        String message,
        String beforeValue,
        String afterValue
) {
    public InvariantViolation {
        Objects.requireNonNull(invariantName, "invariantName");
        Objects.requireNonNull(jsonPointer, "jsonPointer");
        Objects.requireNonNull(message, "message");
    }
}
