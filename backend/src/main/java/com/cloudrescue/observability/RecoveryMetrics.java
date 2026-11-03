package com.cloudrescue.observability;

import com.cloudrescue.incident.IncidentState;
import com.cloudrescue.policy.ActionType;
import com.cloudrescue.policy.FailureType;
import com.cloudrescue.remediation.VerificationOutcome;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Records the recovery-engine metrics. Names use Micrometer dot style; the Prometheus endpoint renders them as
 * cloudrescue_incidents_detected_total, cloudrescue_recovery_duration_seconds, and so on.
 *
 * Every label has a small fixed set of values (enums or policy names). Incident ids, error messages and idempotency
 * keys are never used as labels; per-incident detail belongs in the logs and the PostgreSQL audit tables.
 *
 * Callers that run inside a transaction should record through {@link AfterCommit}. Every method here swallows
 * failures, so a broken metrics backend can never break recovery.
 */
@Component
public class RecoveryMetrics {
    private static final Logger log = LoggerFactory.getLogger(RecoveryMetrics.class);
    static final Duration[] DURATION_BUCKETS = {
            Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(5), Duration.ofSeconds(10),
            Duration.ofSeconds(30), Duration.ofSeconds(60), Duration.ofSeconds(120), Duration.ofSeconds(300)};

    private final MeterRegistry registry;
    private final AtomicBoolean warned = new AtomicBoolean();

    public RecoveryMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** cloudrescue.incidents.detected{failure_type}: incidents opened by the monitor. Counter. */
    public void incidentDetected(FailureType type) {
        safely(() -> Counter.builder("cloudrescue.incidents.detected")
                .description("Incidents opened by the monitor")
                .tag("failure_type", label(type))
                .register(registry).increment());
    }

    /**
     * cloudrescue.policy.decisions{policy,decision}: every policy decision (policy = "operator" for forced actions).
     * cloudrescue.recovery.retries: a decision for another action after an earlier action in the same incident epoch
     * did not recover it.
     */
    public void policyDecision(String policy, ActionType decision, boolean afterEarlierAction) {
        safely(() -> {
            Counter.builder("cloudrescue.policy.decisions")
                    .description("Recovery policy decisions")
                    .tag("policy", label(policy))
                    .tag("decision", label(decision))
                    .register(registry).increment();
            if (afterEarlierAction) {
                Counter.builder("cloudrescue.recovery.retries")
                        .description("Further recovery actions chosen after an earlier action did not recover the incident")
                        .register(registry).increment();
            }
        });
    }

    /** cloudrescue.recovery.attempts{action,result}: executed actions. result = success|failure of the action itself. */
    public void recoveryAttempt(ActionType action, boolean actionSucceeded) {
        safely(() -> Counter.builder("cloudrescue.recovery.attempts")
                .description("Executed recovery actions, by whether the action itself succeeded")
                .tag("action", label(action))
                .tag("result", actionSucceeded ? "success" : "failure")
                .register(registry).increment());
    }

    /** cloudrescue.recovery.verification{outcome}: verified | action_failed | postcondition_failed. */
    public void verification(VerificationOutcome outcome) {
        safely(() -> Counter.builder("cloudrescue.recovery.verification")
                .description("Post-action verification outcomes")
                .tag("outcome", label(outcome))
                .register(registry).increment());
    }

    /** cloudrescue.incidents.closed{state}: entries into RECOVERED, FAILED or ESCALATED. */
    public void incidentClosed(IncidentState state) {
        if (state != IncidentState.RECOVERED && state != IncidentState.FAILED && state != IncidentState.ESCALATED) {
            return;
        }
        safely(() -> Counter.builder("cloudrescue.incidents.closed")
                .description("Incidents that reached a final state (a re-opened incident can be counted again)")
                .tag("state", label(state))
                .register(registry).increment());
    }

    /**
     * cloudrescue.recovery.duration{path}: seconds from detection to verified recovery (histogram).
     * path = automatic, or operator_reopened for incidents an operator re-opened (these include waiting time).
     */
    public void recoveryDuration(Duration duration, boolean operatorReopened) {
        safely(() -> Timer.builder("cloudrescue.recovery.duration")
                .description("Time from incident detection to verified recovery")
                .tag("path", operatorReopened ? "operator_reopened" : "automatic")
                .serviceLevelObjectives(DURATION_BUCKETS)
                .register(registry).record(duration));
    }

    void safely(Runnable r) {
        try {
            r.run();
        } catch (RuntimeException e) {
            if (warned.compareAndSet(false, true)) {
                EventLog.warn(log, "metrics.record_failed", "reason", e.toString(),
                        "note", "recovery continues; further failures are logged at debug");
            } else {
                log.debug("Metric recording failed: {}", e.toString());
            }
        }
    }

    private static String label(Enum<?> e) {
        return e.name().toLowerCase(Locale.ROOT);
    }

    private static String label(String s) {
        return s.toLowerCase(Locale.ROOT);
    }
}
