package dev.workflowguard.domain;

import com.fasterxml.jackson.core.JsonPointer;

import java.util.Objects;

public record JsonInvariant(
        String name,
        String jsonPointer,
        InvariantRule rule,
        String expectedJson
) {
    private static final int MAXIMUM_NAME_LENGTH = 256;
    private static final int MAXIMUM_POINTER_LENGTH = 4_096;
    private static final int MAXIMUM_EXPECTED_VALUE_LENGTH = 65_536;
    private static final int MAXIMUM_EXPRESSION_LENGTH = 1_024;

    public JsonInvariant {
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        if (name.length() > MAXIMUM_NAME_LENGTH) {
            throw new IllegalArgumentException("Invariant name is too long");
        }
        Objects.requireNonNull(jsonPointer, "jsonPointer");
        if (!jsonPointer.isEmpty() && !jsonPointer.startsWith("/")) {
            throw new IllegalArgumentException("jsonPointer must be empty or start with '/'");
        }
        if (jsonPointer.length() > MAXIMUM_POINTER_LENGTH) {
            throw new IllegalArgumentException("jsonPointer is too long");
        }
        JsonPointer.compile(jsonPointer);
        Objects.requireNonNull(rule, "rule");
        if ((rule == InvariantRule.EQUALS
                || rule == InvariantRule.MUST_NOT_CONTAIN
                || rule == InvariantRule.EXPRESSION)
                && (expectedJson == null || expectedJson.isBlank())) {
            throw new IllegalArgumentException(rule + " requires expectedJson");
        }
        if (expectedJson != null
                && expectedJson.length() > MAXIMUM_EXPECTED_VALUE_LENGTH) {
            throw new IllegalArgumentException("expectedJson exceeds the 64 KiB limit");
        }
        if (rule == InvariantRule.EXPRESSION
                && expectedJson.length() > MAXIMUM_EXPRESSION_LENGTH) {
            throw new IllegalArgumentException(
                    "Invariant expression exceeds the 1024 character limit"
            );
        }
    }
}
