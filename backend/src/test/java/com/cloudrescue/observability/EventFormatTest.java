package com.cloudrescue.observability;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventFormatTest {
    @Test
    void formatsEventAndFieldsInOrder() {
        assertEquals("event=incident.detected incident_id=12 service=payment-service failure_type=UNAVAILABLE",
                EventFormat.format("incident.detected", "incident_id", 12L, "service", "payment-service",
                        "failure_type", "UNAVAILABLE"));
    }

    @Test
    void skipsNullValues() {
        assertEquals("event=x a=1", EventFormat.format("x", "a", 1, "fault_id", null));
    }

    @Test
    void quotesValuesWithSpacesAndEqualsSigns() {
        assertEquals("event=x reason=\"one quick retry\"", EventFormat.format("x", "reason", "one quick retry"));
        assertEquals("event=x v=\"a=b\"", EventFormat.format("x", "v", "a=b"));
    }

    @Test
    void redactsSensitiveKeys() {
        String line = EventFormat.format("x", "db_password", "hunter2", "api_token", "abc", "service", "auth");
        assertFalse(line.contains("hunter2"));
        assertFalse(line.contains("abc"));
        assertTrue(line.contains("db_password=[REDACTED]"));
        assertTrue(line.contains("service=auth"));
    }

    @Test
    void masksSecretLookingAssignmentsInsideValues() {
        String line = EventFormat.format("x", "error", "connect failed: password=hunter2 host=db");
        assertFalse(line.contains("hunter2"), line);
        assertTrue(line.contains("password=[REDACTED]"), line);
    }

    @Test
    void removesNewlinesSoOneEventStaysOneLine() {
        String line = EventFormat.format("x", "error", "line1\nline2\r\nline3");
        assertFalse(line.contains("\n"));
        assertFalse(line.contains("\r"));
    }

    @Test
    void truncatesVeryLongValues() {
        String line = EventFormat.format("x", "error", "z".repeat(1000));
        assertTrue(line.length() < 400, "length " + line.length());
        assertTrue(line.endsWith("..."));
    }
}
