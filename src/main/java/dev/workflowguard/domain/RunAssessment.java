package dev.workflowguard.domain;

public enum RunAssessment {
    VERIFIED,
    MUTATION_VIOLATION,
    MUTATION_VIOLATION_RESTORED,
    CLEANUP_FAILED,
    NOT_EVALUATED,
    EXECUTION_FAILED,
    BLOCKED,
    CANCELLED
}
