package dev.workflowguard.domain;

public enum ExecutionPhase {
    BEFORE_PROBE,
    MUTATION,
    AFTER_PROBE,
    CLEANUP,
    POST_CLEANUP_PROBE
}
