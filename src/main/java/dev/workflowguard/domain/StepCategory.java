package dev.workflowguard.domain;

public enum StepCategory {
    READ(false),
    CREATE(true),
    UPDATE(true),
    DELETE(true),
    PAYMENT(true),
    INVITE(true),
    ONE_TIME_USE(true),
    REVOKE(true),
    UNKNOWN(true);

    private final boolean stateChanging;

    StepCategory(boolean stateChanging) {
        this.stateChanging = stateChanging;
    }

    public boolean isStateChanging() {
        return stateChanging;
    }
}
