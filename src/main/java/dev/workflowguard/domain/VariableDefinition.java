package dev.workflowguard.domain;

import com.fasterxml.jackson.core.JsonPointer;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

public record VariableDefinition(
        String name,
        UUID sourceStepId,
        ExtractionType extractionType,
        String expression,
        int captureGroup,
        String staleValue
) {
    private static final Pattern VALID_NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_.-]*");
    private static final int MAXIMUM_NAME_LENGTH = 128;
    private static final int MAXIMUM_EXPRESSION_LENGTH = 4_096;
    private static final int MAXIMUM_STALE_VALUE_LENGTH = 65_536;

    public VariableDefinition {
        Objects.requireNonNull(name, "name");
        if (name.length() > MAXIMUM_NAME_LENGTH || !VALID_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Invalid variable name: " + name);
        }
        Objects.requireNonNull(sourceStepId, "sourceStepId");
        Objects.requireNonNull(extractionType, "extractionType");
        Objects.requireNonNull(expression, "expression");
        if (expression.isBlank()) {
            throw new IllegalArgumentException("expression must not be blank");
        }
        if (expression.length() > MAXIMUM_EXPRESSION_LENGTH) {
            throw new IllegalArgumentException("expression exceeds the 4096 character limit");
        }
        if (extractionType == ExtractionType.REGEX && captureGroup < 0) {
            throw new IllegalArgumentException("captureGroup must be zero or greater");
        }
        if (extractionType == ExtractionType.REGEX) {
            try {
                int groups = com.google.re2j.Pattern.compile(expression).matcher("").groupCount();
                if (captureGroup > groups) {
                    throw new IllegalArgumentException(
                            "captureGroup exceeds the number of groups in the RE2/J expression"
                    );
                }
            } catch (com.google.re2j.PatternSyntaxException exception) {
                throw new IllegalArgumentException(
                        "Regex extraction must use valid RE2/J syntax",
                        exception
                );
            }
        } else {
            try {
                JsonPointer.compile(expression);
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException(
                        "JSON Pointer extraction expression is invalid",
                        exception
                );
            }
        }
        staleValue = staleValue == null ? "" : staleValue;
        if (staleValue.length() > MAXIMUM_STALE_VALUE_LENGTH) {
            throw new IllegalArgumentException("staleValue exceeds the 64 KiB limit");
        }
        if (staleValue.chars().anyMatch(character ->
                Character.isISOControl(character) || character == 0x7f
        )) {
            throw new IllegalArgumentException(
                    "staleValue must not contain control characters"
            );
        }
    }

    public VariableDefinition(
            String name,
            UUID sourceStepId,
            ExtractionType extractionType,
            String expression,
            int captureGroup
    ) {
        this(name, sourceStepId, extractionType, expression, captureGroup, "");
    }

    public boolean hasStaleValue() {
        return !staleValue.isBlank();
    }
}
