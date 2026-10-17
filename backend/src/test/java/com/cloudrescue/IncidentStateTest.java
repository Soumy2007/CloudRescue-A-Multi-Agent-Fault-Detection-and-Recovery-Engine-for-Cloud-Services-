package com.cloudrescue;

import com.cloudrescue.incident.IncidentState;
import org.junit.jupiter.api.Test;

import static com.cloudrescue.incident.IncidentState.*;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IncidentStateTest {
    @Test
    void allowsDocumentedTransitions() {
        assertTrue(DETECTED.canTransitionTo(ANALYZING));
        assertTrue(ANALYZING.canTransitionTo(REMEDIATING));
        assertTrue(ANALYZING.canTransitionTo(ESCALATED));
        assertTrue(REMEDIATING.canTransitionTo(RECOVERED));
        assertTrue(REMEDIATING.canTransitionTo(ANALYZING));
        assertTrue(REMEDIATING.canTransitionTo(FAILED));
        assertTrue(FAILED.canTransitionTo(ANALYZING));
    }

    @Test
    void rejectsEverythingElse() {
        assertFalse(DETECTED.canTransitionTo(RECOVERED));
        assertFalse(DETECTED.canTransitionTo(REMEDIATING));
        assertFalse(ANALYZING.canTransitionTo(RECOVERED));
        assertFalse(FAILED.canTransitionTo(RECOVERED));
        for (IncidentState s : IncidentState.values()) {
            assertFalse(RECOVERED.canTransitionTo(s), "RECOVERED is terminal");
        }
    }
}
