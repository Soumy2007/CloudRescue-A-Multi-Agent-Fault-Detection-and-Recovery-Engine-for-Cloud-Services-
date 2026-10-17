package com.cloudrescue.policy;

/** What a policy can ask the executor to do. ESCALATE and GIVE_UP are terminal decisions, not actions. */
public enum ActionType {
    RETRY, RESTART, FAILOVER, ESCALATE, GIVE_UP;

    public boolean isExecutable() {
        return this == RETRY || this == RESTART || this == FAILOVER;
    }
}
