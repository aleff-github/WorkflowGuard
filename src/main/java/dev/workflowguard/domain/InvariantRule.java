package dev.workflowguard.domain;

public enum InvariantRule {
    UNCHANGED,
    ABSENT,
    EQUALS,
    ARRAY_SIZE_UNCHANGED,
    MUST_NOT_CONTAIN,
    EXPRESSION
}
