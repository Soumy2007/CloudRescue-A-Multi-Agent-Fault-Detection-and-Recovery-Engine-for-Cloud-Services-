package com.cloudrescue.observability;

import com.cloudrescue.PostgresIT;
import com.cloudrescue.policy.ActionType;
import com.cloudrescue.policy.FailureType;
import com.cloudrescue.remediation.VerificationOutcome;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.ResponseEntity;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The HTTP surface: Prometheus scrape endpoint, health, the dashboard summary API, and that unneeded actuator
 * endpoints stay hidden. @AutoConfigureObservability re-enables metric export, which Spring Boot disables in tests.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "cloudrescue.workers.enabled=false")
@AutoConfigureObservability
class ObservabilityEndpointIT extends PostgresIT {
    @Autowired TestRestTemplate rest;
    @Autowired RecoveryMetrics metrics;
    @Autowired AvailabilityTracker availability;

    @Test
    void prometheusEndpointServesTheRecoveryMetrics() {
        availability.record("payment-service", true);   // so the availability gauge has a real sample
        metrics.incidentDetected(FailureType.UNAVAILABLE);
        metrics.recoveryAttempt(ActionType.RESTART, true);
        metrics.verification(VerificationOutcome.VERIFIED);
        metrics.recoveryDuration(Duration.ofSeconds(12), false);

        ResponseEntity<String> response = rest.getForEntity("/actuator/prometheus", String.class);

        assertEquals(200, response.getStatusCode().value());
        String body = response.getBody();
        assertNotNull(body);
        for (String expected : new String[] {
                "cloudrescue_incidents_detected_total", "cloudrescue_recovery_attempts_total",
                "cloudrescue_recovery_verification_total", "cloudrescue_recovery_duration_seconds_count",
                "cloudrescue_active_incidents", "cloudrescue_service_availability_ratio",
                "application=\"cloudrescue\""}) {
            assertTrue(body.contains(expected), "Prometheus output is missing " + expected);
        }
    }

    @Test
    void healthAndMetricsEndpointsStillWork() {
        ResponseEntity<String> health = rest.getForEntity("/actuator/health", String.class);
        assertEquals(200, health.getStatusCode().value());
        assertTrue(health.getBody().contains("UP"));
        assertEquals(200, rest.getForEntity("/actuator/metrics/cloudrescue.active.incidents", String.class)
                .getStatusCode().value());
    }

    @Test
    void unneededActuatorEndpointsAreNotExposed() {
        for (String path : new String[] {"/actuator/env", "/actuator/beans", "/actuator/heapdump", "/actuator/loggers"}) {
            assertEquals(404, rest.getForEntity(path, String.class).getStatusCode().value(), path + " must not be exposed");
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void summaryApiReturnsRealCountsAndLabelsThemAsSimulated() {
        Map<String, Object> body = rest.getForObject("/api/observability/summary", Map.class);

        assertNotNull(body);
        assertTrue(((Number) body.get("totalIncidents")).longValue() >= 0);
        assertTrue(body.containsKey("verificationOutcomes"));
        assertTrue(body.containsKey("recoveryDuration"));
        Map<String, Object> meta = (Map<String, Object>) body.get("meta");
        assertEquals("simulated", meta.get("measurement"));
    }
}
