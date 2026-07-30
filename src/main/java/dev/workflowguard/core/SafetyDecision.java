package dev.workflowguard.core;

import java.util.List;
import java.util.Objects;

public record SafetyDecision(boolean allowed, List<String> reasons) {
    public SafetyDecision {
        reasons = List.copyOf(Objects.requireNonNull(reasons, "reasons"));
        if (allowed && !reasons.isEmpty()) {
            throw new IllegalArgumentException("An allowed decision cannot contain rejection reasons");
        }
        if (!allowed && reasons.isEmpty()) {
            throw new IllegalArgumentException("A rejected decision requires at least one reason");
        }
    }
}
