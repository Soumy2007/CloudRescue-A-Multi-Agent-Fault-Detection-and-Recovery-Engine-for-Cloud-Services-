package com.cloudrescue.policy;

import java.util.List;

/** A decision plus the explanation: why it was chosen and which alternatives were rejected. */
public record RecoveryDecision(ActionType action, long delayMs, String reason, List<String> rejected) {
    public static RecoveryDecision of(ActionType a, long delayMs, String reason, String... rejected) {
        return new RecoveryDecision(a, delayMs, reason, List.of(rejected));
    }
}
