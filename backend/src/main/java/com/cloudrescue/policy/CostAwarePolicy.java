package com.cloudrescue.policy;

/**
 * Picks the cheapest action that can plausibly fix the observed failure type:
 * region-level failures skip straight to failover (restarts cannot help), crashes skip long retry
 * waits, and soft degradations get a couple of short retries first.
 */
public class CostAwarePolicy extends BasePolicy {

    @Override public String name() { return "COST_AWARE"; }

    @Override public String description() {
        return "Chooses the cheapest action likely to work for the observed failure type; "
                + "fails over immediately on suspected region outages.";
    }

    @Override
    protected RecoveryDecision choose(PolicyContext c) {
        switch (c.failureType()) {
            case REGION_SUSPECTED:
                if (c.failovers() == 0) {
                    if (c.backupHealthy()) {
                        return RecoveryDecision.of(ActionType.FAILOVER, 0,
                                "Region-wide failure suspected; restarting in the same region cannot help",
                                "RETRY: region is down", "RESTART: region is down");
                    }
                    return RecoveryDecision.of(ActionType.ESCALATE, 0,
                            "Region failure but no healthy backup; failover rejected",
                            "FAILOVER: no healthy backup available");
                }
                return RecoveryDecision.of(ActionType.ESCALATE, 0, "Already failed over once and still failing");
            case UNAVAILABLE:
                if (c.retries() == 0 && c.restarts() == 0) {
                    return RecoveryDecision.of(ActionType.RETRY, 1000,
                            "One quick retry in case the outage is transient",
                            "FAILOVER: cheaper actions not yet tried");
                }
                if (c.restarts() < 2) {
                    return RecoveryDecision.of(ActionType.RESTART, 0,
                            "Still unavailable after retry; restart " + (c.restarts() + 1) + "/2",
                            "RETRY: already tried once");
                }
                break;
            default: // HIGH_LATENCY, HIGH_ERROR_RATE
                if (c.retries() < 2) {
                    long delay = 2000L << c.retries();
                    return RecoveryDecision.of(ActionType.RETRY, delay,
                            "Soft degradation; short retry " + (c.retries() + 1) + "/2 may outlast it",
                            "RESTART: degradation may be transient");
                }
                if (c.restarts() < 1) {
                    return RecoveryDecision.of(ActionType.RESTART, 0,
                            "Retries did not clear degradation; restart", "FAILOVER: restart is cheaper");
                }
        }
        if (c.backupHealthy() && c.failovers() == 0) {
            return RecoveryDecision.of(ActionType.FAILOVER, 0,
                    "Retries and restarts failed; failing over to the healthy backup region");
        }
        return RecoveryDecision.of(ActionType.GIVE_UP, 0,
                c.backupHealthy() ? "All strategies exhausted" : "All strategies exhausted; no healthy backup for failover");
    }
}
