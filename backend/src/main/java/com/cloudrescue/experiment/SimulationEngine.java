package com.cloudrescue.experiment;

import com.cloudrescue.policy.ActionType;
import com.cloudrescue.policy.CostModel;
import com.cloudrescue.policy.FailureType;
import com.cloudrescue.policy.PolicyContext;
import com.cloudrescue.policy.RecoveryDecision;
import com.cloudrescue.policy.RecoveryPolicy;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Random;

/**
 * Deterministic discrete-time (1 tick = 1 simulated second) model of one service with a primary in
 * Region A and a backup in Region B. It runs the SAME RecoveryPolicy classes as the live system, but
 * on a virtual clock, so hundreds of trials finish in milliseconds and are exactly reproducible
 * from a seed.
 *
 * Scenario randomness (fault duration) and action randomness use separate generators so every policy
 * faces the same fault for the same trial index.
 *
 * Duplicate-operation model: when an action's acknowledgement is lost, the caller retries the same
 * logical operation. With idempotency the stored result is returned (1s lookup, no second effect).
 * Without it the effect is applied again (counted as a duplicate) and the action time is paid twice.
 * The real database-backed idempotency implementation is covered by the integration tests.
 */
public final class SimulationEngine {

    public enum FaultKind { ERROR_BURST, CRASH, LATENCY_SPIKE, REGION_OUTAGE }

    public record Scenario(String name, String description, FaultKind kind, int faultStartSec,
                           int baseDurationSec, int durationJitterSec, double restartFailureProb,
                           double ackLossProb, boolean backupHealthy, int observationSec) {}

    /** Nullable Double fields are null when the metric is undefined (e.g. never recovered). */
    public record TrialResult(boolean detected, boolean recovered, boolean escalated,
                              Double detectionDelaySec, Double recoveryTimeSec, Double totalTimeSec,
                              double availability, int duplicateOps, double cost,
                              int retries, int restarts, int failovers) {}

    private enum Phase { MONITORING, ANALYZING, ACTING, VERIFYING, DONE, GAVE_UP }

    private static final int DETECT_WINDOW = 5;
    private static final int DETECT_FAILURES = 3;
    private static final int VERIFY_PROBES = 3;

    private SimulationEngine() {}

    public static List<Scenario> defaultScenarios() {
        return List.of(
                new Scenario("TRANSIENT_ERRORS", "90% error burst lasting 12-22s",
                        FaultKind.ERROR_BURST, 30, 12, 10, 0.1, 0, true, 300),
                new Scenario("LATENCY_SPIKE", "Latency above the probe timeout for 20-40s",
                        FaultKind.LATENCY_SPIKE, 30, 20, 20, 0.1, 0, true, 300),
                new Scenario("SERVICE_CRASH", "Crash cleared only by restart; restart fails 20% of the time",
                        FaultKind.CRASH, 30, 0, 0, 0.2, 0, true, 300),
                new Scenario("CRASH_RESTART_ALWAYS_FAILS", "Crash where every restart attempt fails",
                        FaultKind.CRASH, 30, 0, 0, 1.0, 0, true, 300),
                new Scenario("REGION_OUTAGE", "Region A unavailable for 150s",
                        FaultKind.REGION_OUTAGE, 30, 150, 0, 0.0, 0, true, 300),
                new Scenario("CRASH_LOST_ACKS", "Crash; 50% of restart acknowledgements are lost",
                        FaultKind.CRASH, 30, 0, 0, 0.0, 0.5, true, 300));
    }

    static int actionDurationSec(ActionType t) {
        return switch (t) {
            case RETRY -> 1;
            case RESTART -> 5;
            case FAILOVER -> 8;
            default -> 0;
        };
    }

    private static FailureType classify(FaultKind k) {
        return switch (k) {
            case ERROR_BURST -> FailureType.HIGH_ERROR_RATE;
            case LATENCY_SPIKE -> FailureType.HIGH_LATENCY;
            case CRASH -> FailureType.UNAVAILABLE;
            case REGION_OUTAGE -> FailureType.REGION_SUSPECTED;
        };
    }

    public static TrialResult run(Scenario sc, RecoveryPolicy policy, long seed, boolean idempotency) {
        return new Run(sc, policy, seed, idempotency).execute();
    }

    private static final class Run {
        final Scenario sc;
        final RecoveryPolicy policy;
        final boolean idem;
        final Random rng;
        final int start;
        final int duration;
        boolean cleared;
        boolean onBackup;

        Run(Scenario sc, RecoveryPolicy policy, long seed, boolean idem) {
            this.sc = sc;
            this.policy = policy;
            this.idem = idem;
            Random scenarioRng = new Random(seed);
            this.rng = new Random(seed * 31 + 7);
            this.start = sc.faultStartSec();
            this.duration = sc.baseDurationSec() == 0 ? 0
                    : sc.baseDurationSec() + (sc.durationJitterSec() > 0 ? scenarioRng.nextInt(sc.durationJitterSec() + 1) : 0);
        }

        boolean faultActive(int t) {
            return t >= start && (duration == 0 || t < start + duration);
        }

        boolean probe(int t) {
            if (onBackup) return sc.backupHealthy();
            if (!faultActive(t)) return true;
            return switch (sc.kind()) {
                case REGION_OUTAGE -> false;
                case CRASH, LATENCY_SPIKE -> cleared;
                case ERROR_BURST -> cleared || rng.nextDouble() >= 0.9;
            };
        }

        TrialResult execute() {
            Deque<Boolean> window = new ArrayDeque<>();
            Phase phase = Phase.MONITORING;
            int okTicks = 0, retries = 0, restarts = 0, failovers = 0, dup = 0;
            int detectedAt = -1, recoveredAt = -1, actionEnd = 0, verifyAt = 0;
            double cost = 0;
            boolean escalated = false;
            boolean actionOk = false;     // did the last executed action itself succeed?
            ActionType pending = null;
            FailureType ftype = classify(sc.kind());

            for (int t = 0; t < sc.observationSec(); t++) {
                boolean ok = probe(t);
                if (ok) okTicks++;
                window.addLast(ok);
                if (window.size() > DETECT_WINDOW) window.removeFirst();
                long fails = window.stream().filter(b -> !b).count();

                if (phase == Phase.MONITORING && fails >= DETECT_FAILURES) {
                    phase = Phase.ANALYZING;
                    detectedAt = t;
                }

                if (phase == Phase.ANALYZING) {
                    RecoveryDecision d = policy.decide(new PolicyContext(
                            ftype, retries, restarts, failovers, t - detectedAt, sc.backupHealthy()));
                    switch (d.action()) {
                        case ESCALATE -> { escalated = true; phase = Phase.GAVE_UP; }
                        case GIVE_UP -> phase = Phase.GAVE_UP;
                        default -> {
                            pending = d.action();
                            actionEnd = t + (int) Math.ceil(d.delayMs() / 1000.0) + actionDurationSec(pending);
                            phase = Phase.ACTING;
                        }
                    }
                } else if (phase == Phase.ACTING && t >= actionEnd) {
                    boolean applied = false;
                    switch (pending) {
                        case RETRY -> {
                            retries++;
                            actionOk = probe(t);   // a retry succeeds only if the health check passes
                        }
                        case RESTART -> {
                            restarts++;
                            boolean regionDown = sc.kind() == FaultKind.REGION_OUTAGE && faultActive(t);
                            if (!regionDown && rng.nextDouble() >= sc.restartFailureProb()) {
                                cleared = true;
                                applied = true;
                            }
                        }
                        case FAILOVER -> {
                            failovers++;
                            if (sc.backupHealthy()) {
                                onBackup = true;
                                applied = true;
                            }
                        }
                        default -> { }
                    }
                    if (pending != ActionType.RETRY) actionOk = applied;
                    cost += CostModel.of(pending);
                    int extra = 0;
                    if (applied && rng.nextDouble() < sc.ackLossProb()) {
                        if (idem) {
                            extra = 1;                       // reconcile: stored result returned
                        } else {
                            dup++;                           // naive re-execution applies the effect twice
                            cost += CostModel.of(pending);
                            extra = actionDurationSec(pending);
                        }
                    }
                    verifyAt = t + 1 + extra;
                    phase = Phase.VERIFYING;
                } else if (phase == Phase.VERIFYING && t >= verifyAt) {
                    boolean healthy = true;
                    for (int i = 0; i < VERIFY_PROBES; i++) healthy &= probe(t);
                    // Recovery needs a successful action AND healthy probes: a failed action is never
                    // credited with a recovery just because a later probe happened to pass.
                    if (healthy && actionOk) {
                        recoveredAt = t;
                        phase = Phase.DONE;
                    } else {
                        phase = Phase.ANALYZING;
                    }
                }
            }

            boolean detected = detectedAt >= 0;
            boolean recovered = recoveredAt >= 0;
            return new TrialResult(detected, recovered, escalated,
                    detected ? (double) (detectedAt - start) : null,
                    recovered ? (double) (recoveredAt - detectedAt) : null,
                    recovered ? (double) (recoveredAt - start) : null,
                    (double) okTicks / sc.observationSec(), dup, cost, retries, restarts, failovers);
        }
    }
}
