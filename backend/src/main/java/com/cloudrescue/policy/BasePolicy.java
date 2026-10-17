package com.cloudrescue.policy;

/** Shared safeguards against recovery loops: a global action cap and a global time budget. */
public abstract class BasePolicy implements RecoveryPolicy {
    public static final int MAX_TOTAL_ACTIONS = 8;
    public static final long MAX_ELAPSED_SEC = 240;

    @Override
    public final RecoveryDecision decide(PolicyContext c) {
        if (c.totalActions() >= MAX_TOTAL_ACTIONS) {
            return RecoveryDecision.of(ActionType.GIVE_UP, 0,
                    "Loop guard: " + c.totalActions() + " actions already attempted");
        }
        if (c.elapsedSec() > MAX_ELAPSED_SEC) {
            return RecoveryDecision.of(ActionType.ESCALATE, 0,
                    "Time budget of " + MAX_ELAPSED_SEC + "s exceeded; human intervention required");
        }
        return choose(c);
    }

    protected abstract RecoveryDecision choose(PolicyContext c);
}
