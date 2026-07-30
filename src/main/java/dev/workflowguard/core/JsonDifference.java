package dev.workflowguard.core;

import java.util.Objects;

public record JsonDifference(
        String jsonPointer,
        DifferenceKind kind,
        String beforeValue,
        String afterValue
) {
    public JsonDifference {
        Objects.requireNonNull(jsonPointer, "jsonPointer");
        Objects.requireNonNull(kind, "kind");
    }
}
