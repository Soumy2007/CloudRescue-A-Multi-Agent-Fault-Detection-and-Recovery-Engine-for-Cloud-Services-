package com.cloudrescue.api;

import com.cloudrescue.fault.FaultInjection;
import com.cloudrescue.fault.FaultType;
import com.cloudrescue.incident.Incident;
import com.cloudrescue.incident.IncidentTransition;
import com.cloudrescue.policy.ActionType;
import com.cloudrescue.policy.PolicyRun;
import com.cloudrescue.remediation.RemediationAction;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/** Request/response shapes. Persistence entities are never returned directly. */
public final class Dtos {
    private Dtos() {}

    public record IncidentDto(Long id, String serviceName, String failureType, String state, String policyName,
                              Instant detectedAt, Instant recoveredAt, Double recoverySec, Long faultInjectionId,
                              int epoch, String summary) {
        public static IncidentDto of(Incident i) {
            return new IncidentDto(i.id, i.serviceName, i.failureType.name(), i.state.name(), i.policyName,
                    i.detectedAt, i.recoveredAt,
                    i.recoveredAt == null || i.epoch > 0 ? null : Duration.between(i.detectedAt, i.recoveredAt).toMillis() / 1000.0,
                    i.faultInjectionId, i.epoch, i.summary);
        }
    }

    public record TransitionDto(String from, String to, String reason, Instant at) {
        public static TransitionDto of(IncidentTransition t) {
            return new TransitionDto(t.fromState == null ? null : t.fromState.name(), t.toState.name(), t.reason, t.occurredAt);
        }
    }

    public record ActionDto(Long id, int seqNo, String type, String status, String idempotencyKey, Instant scheduledAt,
                            Instant startedAt, Instant finishedAt, Long durationMs, int attempts, String result,
                            Boolean verifiedHealthy, String outcome, Long targetNodeId, Long expectedActiveNodeId) {
        public static ActionDto of(RemediationAction a) {
            return new ActionDto(a.id, a.seqNo, a.actionType.name(), a.status.name(), a.idempotencyKey, a.scheduledAt,
                    a.startedAt, a.finishedAt, a.durationMs, a.attempts, a.resultMessage, a.verifiedHealthy,
                    a.outcome == null ? null : a.outcome.name(), a.targetNodeId, a.expectedActiveNodeId);
        }
    }

    public record DecisionDto(String policy, String failureType, String action, long delayMs, String reason,
                              String rejected, Instant at) {
        public static DecisionDto of(PolicyRun p) {
            return new DecisionDto(p.policyName, p.failureType.name(), p.decisionAction.name(), p.delayMs, p.reason,
                    p.rejectedAlternatives, p.decidedAt);
        }
    }

    public record IncidentDetail(IncidentDto incident, List<TransitionDto> transitions, List<ActionDto> actions,
                                 List<DecisionDto> decisions) {}

    public record FaultDto(Long id, String type, Long nodeId, String region, double severity, Instant startedAt,
                           Instant scheduledEndAt, Instant endedAt, String endReason) {
        public static FaultDto of(FaultInjection f) {
            return new FaultDto(f.id, f.faultType.name(), f.targetNodeId, f.targetRegion, f.severity, f.startedAt,
                    f.scheduledEndAt, f.endedAt, f.endReason);
        }
    }

    public record FaultRequest(@NotNull FaultType type, Long nodeId, String region, Double severity,
                               @Min(1) @Max(3600) Integer durationSec) {}

    public record ReopenRequest(ActionType action) {}

    public record PolicyInfo(String name, String description, boolean active) {}

    public record SetPolicyRequest(@NotBlank String name) {}

    public record ExperimentRequest(List<String> scenarios, List<String> policies, @Min(1) @Max(200) Integer trials,
                                    Long seed, Boolean idempotencyEnabled) {}

    public record PageDto<T>(List<T> items, int page, int size, long total) {}
}
