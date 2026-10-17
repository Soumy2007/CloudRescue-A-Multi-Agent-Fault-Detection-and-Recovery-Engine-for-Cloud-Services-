package com.cloudrescue.policy;

/** Retries with exponentially growing delay, then restarts the service. Never fails over. */
public class ExponentialBackoffPolicy extends BasePolicy {
    private static final int MAX_RETRIES = 4;
    private static final int MAX_RESTARTS = 2;
    private static final long BASE_MS = 2000;
    private static final long CAP_MS = 30000;

    @Override public String name() { return "EXPONENTIAL_BACKOFF"; }

    @Override public String description() {
        return "Retries after 2s, 4s, 8s, 16s, then restarts up to twice. Never fails over.";
    }

    @Override
    protected RecoveryDecision choose(PolicyContext c) {
        if (c.retries() < MAX_RETRIES) {
            long delay = Math.min(CAP_MS, BASE_MS << c.retries());
            return RecoveryDecision.of(ActionType.RETRY, delay,
                    "Backoff retry " + (c.retries() + 1) + "/" + MAX_RETRIES + " after " + delay + "ms",
                    "RESTART: retries not yet exhausted", "FAILOVER: not supported by this policy");
        }
        if (c.restarts() < MAX_RESTARTS) {
            return RecoveryDecision.of(ActionType.RESTART, 1000,
                    "Retries exhausted; restart " + (c.restarts() + 1) + "/" + MAX_RESTARTS,
                    "RETRY: backoff schedule exhausted", "FAILOVER: not supported by this policy");
        }
        return RecoveryDecision.of(ActionType.GIVE_UP, 0, "Retries and restarts exhausted");
    }
}
