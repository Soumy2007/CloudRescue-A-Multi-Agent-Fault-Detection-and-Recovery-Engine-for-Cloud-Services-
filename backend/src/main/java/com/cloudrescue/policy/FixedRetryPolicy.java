package com.cloudrescue.policy;

/** Baseline: retry at a fixed interval, never escalate to a stronger action. */
public class FixedRetryPolicy extends BasePolicy {
    private static final int MAX_RETRIES = 5;
    private static final long DELAY_MS = 3000;

    @Override public String name() { return "FIXED_RETRY"; }

    @Override public String description() {
        return "Retries every 3s up to 5 times. Never restarts or fails over.";
    }

    @Override
    protected RecoveryDecision choose(PolicyContext c) {
        if (c.retries() < MAX_RETRIES) {
            return RecoveryDecision.of(ActionType.RETRY, DELAY_MS,
                    "Retry " + (c.retries() + 1) + "/" + MAX_RETRIES + " at fixed " + DELAY_MS + "ms interval",
                    "RESTART: not supported by this policy", "FAILOVER: not supported by this policy");
        }
        return RecoveryDecision.of(ActionType.GIVE_UP, 0, "Retry limit of " + MAX_RETRIES + " reached");
    }
}
