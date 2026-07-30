package dev.workflowguard.domain;

import java.time.Duration;
import java.util.Objects;

public record ExecutionPolicy(
        int maximumRequests,
        Duration delayBetweenRequests,
        boolean requireStateChangingConfirmation,
        boolean inScopeOnly
) {
    public static final int ABSOLUTE_MAXIMUM_REQUESTS = 100;

    public ExecutionPolicy {
        if (maximumRequests < 1) {
            throw new IllegalArgumentException("maximumRequests must be positive");
        }
        if (maximumRequests > ABSOLUTE_MAXIMUM_REQUESTS) {
            throw new IllegalArgumentException(
                    "maximumRequests must not exceed " + ABSOLUTE_MAXIMUM_REQUESTS
            );
        }
        Objects.requireNonNull(delayBetweenRequests, "delayBetweenRequests");
        if (delayBetweenRequests.isNegative()) {
            throw new IllegalArgumentException("delayBetweenRequests must not be negative");
        }
    }

    public static ExecutionPolicy conservativeDefaults() {
        return new ExecutionPolicy(20, Duration.ofSeconds(1), true, true);
    }
}
