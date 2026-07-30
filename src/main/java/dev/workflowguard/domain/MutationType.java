package dev.workflowguard.domain;

public enum MutationType {
    REPLAY_AFTER_REVOKE,
    REPLAY_AFTER_DELETE,
    REPLAY_ONE_TIME_USE,
    SKIP_STEP,
    REPEAT_STEP,
    REPLAY_EARLIER_STEP,
    SWAP_ACTOR,
    STALE_VARIABLE
}
