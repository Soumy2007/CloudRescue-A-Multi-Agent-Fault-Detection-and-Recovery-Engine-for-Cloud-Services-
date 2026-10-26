package com.cloudrescue.remediation;

import com.cloudrescue.remediation.RecoveryRules.Observation;
import org.junit.jupiter.api.Test;

import static com.cloudrescue.remediation.VerificationOutcome.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

class RecoveryRulesTest {
    private static final long A = 1L, B = 2L;

    @Test
    void successfulActionOnExpectedHealthyNodeIsVerified() {
        assertEquals(VERIFIED, RecoveryRules.evaluate(new Observation(true, A, A, true)));
    }

    @Test
    void failedActionNeverRecoversEvenIfTheNodeNowAnswers() {
        // The false-recovery bug: action failed, but a later probe passed.
        assertEquals(ACTION_FAILED, RecoveryRules.evaluate(new Observation(false, A, A, true)));
    }

    @Test
    void failoverMustLeaveTheIntendedBackupActive() {
        assertEquals(VERIFIED, RecoveryRules.evaluate(new Observation(true, B, B, true)));
        // Action "succeeded" but traffic is still on A, even though A is healthy.
        assertEquals(POSTCONDITION_FAILED, RecoveryRules.evaluate(new Observation(true, B, A, true)));
    }

    @Test
    void expectedNodeMustBeHealthy() {
        assertEquals(POSTCONDITION_FAILED, RecoveryRules.evaluate(new Observation(true, A, A, false)));
    }

    @Test
    void noActiveNodeIsNeverRecovered() {
        assertEquals(POSTCONDITION_FAILED, RecoveryRules.evaluate(new Observation(true, A, null, false)));
        assertEquals(POSTCONDITION_FAILED, RecoveryRules.evaluate(new Observation(true, null, A, true)));
    }
}
