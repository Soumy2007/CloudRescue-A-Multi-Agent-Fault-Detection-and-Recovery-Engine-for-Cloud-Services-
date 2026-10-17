package com.cloudrescue.policy;

/** Pure decision logic. No I/O, no clock, no persistence: execution lives in the remediation module. */
public interface RecoveryPolicy {
    String name();

    String description();

    RecoveryDecision decide(PolicyContext ctx);
}
