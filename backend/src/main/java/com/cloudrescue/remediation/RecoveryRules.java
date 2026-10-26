package com.cloudrescue.remediation;

/**
 * The single rule that decides whether an incident may be marked RECOVERED. Pure logic, no I/O, so it is
 * unit-testable and cannot be bypassed by timing luck in a probe.
 *
 * An incident recovers only if ALL of these hold:
 *  1. the remediation action itself succeeded;
 *  2. the node that is now serving traffic is the node the action was expected to leave serving
 *     (restart/retry: the node acted on; failover: the intended backup);
 *  3. that node answers health probes.
 */
public final class RecoveryRules {
    private RecoveryRules() {}

    public record Observation(boolean actionSucceeded, Long expectedActiveNodeId,
                              Long observedActiveNodeId, boolean observedNodeHealthy) {}

    public static VerificationOutcome evaluate(Observation o) {
        if (!o.actionSucceeded()) {
            return VerificationOutcome.ACTION_FAILED;
        }
        if (o.expectedActiveNodeId() == null || !o.expectedActiveNodeId().equals(o.observedActiveNodeId())) {
            return VerificationOutcome.POSTCONDITION_FAILED;
        }
        return o.observedNodeHealthy() ? VerificationOutcome.VERIFIED : VerificationOutcome.POSTCONDITION_FAILED;
    }
}
