package com.cloudrescue.remediation;

/** Result of verifying a remediation action against its expected post-state. */
public enum VerificationOutcome {
    /** Action succeeded AND the expected node is the active one AND it is healthy. The only outcome that recovers. */
    VERIFIED,
    /** The action itself failed. A later healthy probe must NOT turn this into a recovery. */
    ACTION_FAILED,
    /** The action reported success but the expected state was not observed. */
    POSTCONDITION_FAILED
}
