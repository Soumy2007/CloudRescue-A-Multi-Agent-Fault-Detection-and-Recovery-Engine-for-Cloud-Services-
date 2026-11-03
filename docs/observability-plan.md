# Phase 3 observability plan

Status labels: **Already implemented**, **Modified or extended**, **Newly implemented**, **Deferred**.

## 1. Audit: what existed before Phase 3

| Area | State before Phase 3 |
|---|---|
| Spring Boot Actuator | **Already implemented.** `health`, `info`, `metrics` exposed. Only default JVM/HTTP metrics; no CloudRescue metrics. No Prometheus endpoint. |
| Micrometer | On the classpath through Actuator; **0 custom meters**. |
| Logging | **Already implemented**, but 14 unstructured plain-text statements; no incident/service context; no verification or policy-decision events. |
| Durable audit data (PostgreSQL) | **Already implemented.** `incidents`, `incident_state_transitions`, `remediation_actions` (with Phase 2 `outcome`, targets), `policy_runs`, `idempotency_records`, `fault_injections`, `experiment_runs`. |
| Analytics API | **Already implemented.** `/api/analytics/live` (counts by state, per-policy mean recovery/detection) and `/api/analytics/metrics` (definitions). |
| Dashboard | **Already implemented.** Overview (health board, fault injection), Incidents, Experiments. No observability view. |
| Monitor | **Already implemented.** In-memory probe history per node, used for detection; cleared after recovery (so it cannot show past outages). |

## 2. Gaps addressed

1. No machine-readable metrics for the recovery engine. Newly implemented.
2. Logs could not be followed across detection, decision, action, verification and transition. Modified.
3. Outages disappear from the monitor history after recovery, so availability could not be measured. Newly implemented (a separate tracker).
4. No dashboard view of recovery success, duration, verification failures. Newly implemented.
5. No Prometheus/Grafana option. Newly implemented as an optional compose override (see section 8 for its verification status).

## 3. Metrics (definitions)

Micrometer names use dots. Prometheus renders them as shown.

| Prometheus name | Type | Unit | Labels (bounded) | Meaning | Recorded |
|---|---|---|---|---|---|
| `cloudrescue_incidents_detected_total` | counter | incidents | `failure_type` (4) | Incidents opened by the monitor | `MonitorService`, after the incident row commits |
| `cloudrescue_policy_decisions_total` | counter | decisions | `policy` (3 + `operator`), `decision` (5) | Every policy decision, including ESCALATE/GIVE_UP | `RecoveryWorker.analyze`, after commit |
| `cloudrescue_recovery_retries_total` | counter | decisions | none | A decision for another action after an earlier action in the same epoch did not recover the incident | same |
| `cloudrescue_recovery_attempts_total` | counter | actions | `action` (3), `result` (success/failure) | Executed actions; `result` is whether the action itself succeeded | `RecoveryWorker.remediate`, after commit |
| `cloudrescue_recovery_verification_total` | counter | actions | `outcome` (verified, action_failed, postcondition_failed) | Phase 2 verification outcome per action | same |
| `cloudrescue_incidents_closed_total` | counter | incidents | `state` (recovered, failed, escalated) | Entries into a final state; a re-opened incident can be counted again | same |
| `cloudrescue_recovery_duration_seconds` | histogram | seconds | `path` (automatic, operator_reopened) | Detection to verified recovery; buckets 1, 2, 5, 10, 30, 60, 120, 300 s | on transition to RECOVERED, after commit |
| `cloudrescue_active_incidents` | gauge | incidents | none | Unresolved incidents (every state except RECOVERED), queried from PostgreSQL per scrape | `ObservabilityGauges` |
| `cloudrescue_service_availability_ratio` | gauge | ratio 0..1 | `service` (3) | Successful polls / polls over the last N monitor polls (default 300) of the node serving traffic. **Simulated.** NaN until the first sample | `MonitorService.poll` feeds `AvailabilityTracker` |

Not added: per-region availability (the simulator models regions only through node placement), and a separate retry counter for RETRY actions (already `attempts{action="retry"}`).

Labels never carry incident ids, error text, timestamps or idempotency keys; those are in logs and PostgreSQL.

### Reset and persistence
Counters and histograms live in memory and restart at 0 with the application; Prometheus `rate()`/`increase()` handle that. The durable truth is PostgreSQL. The availability tracker is also in memory and starts empty after a restart.

## 4. PostgreSQL vs metrics vs logs

| Kind | Where | Why |
|---|---|---|
| Durable business/audit records | PostgreSQL (unchanged) | Survive restarts, queryable per incident |
| Operational metrics | Micrometer registry, scraped by Prometheus | Cheap aggregation and alerting; never stored per sample in PostgreSQL |
| Diagnostic events | Logs (`event=... key=value`) | Per-incident detail with identifiers |

**No Flyway migration was needed.** The summary API reads existing tables. Migrations still run V1 to V3 only.

## 5. Failure behavior of the metrics path
* Recording is wrapped: an exception is caught, one `event=metrics.record_failed` WARN is logged, later failures go to debug. Recovery continues.
* Observations inside a recovery transaction go through `AfterCommit`: a step that rolls back and is re-run is observed once; an observation that throws never affects the transaction.
* If the process dies between commit and the after-commit callback, that one observation is lost. PostgreSQL still holds the truth.
* A gauge whose query throws is reported as NaN by Micrometer; the scrape still succeeds.

## 6. Implementation sequence
1. Pure parts first: `EventFormat`, `AvailabilityTracker`, `RecoveryStats` with unit tests.
2. `RecoveryMetrics` and `AfterCommit`; `ObservabilityGauges`.
3. Hook into `MonitorService`, `RecoveryWorker`, `IncidentService`, `FaultService`.
4. Expose endpoints (`prometheus` dependency, `application.yml`), summary API.
5. Dashboard tab.
6. Optional Prometheus/Grafana compose override.
7. Tests and documentation.

## 7. Risks to Phase 2 and how they are contained
* Recovery decisions and verification code paths are unchanged; observation calls only read values already computed.
* Metrics and logs run after commit and swallow their own errors.
* The row lock and `saveAndFlush` claim in `RecoveryWorker` are kept as merged.
* `RecoveryMetricsIT` asserts that a forced failover with an unhealthy backup stays unrecovered **and** produces no `recovered` count and no duration sample.


## 8. Testing strategy and status

| Test | Kind | Verified status |
|---|---|---|
| `EventFormatTest` | Unit | Passed in the Maven test suite |
| `AvailabilityTrackerTest` | Unit | Passed in the Maven test suite |
| `RecoveryStatsTest` | Unit | Passed in the Maven test suite |
| `RecoveryMetricsTest` | Unit | Passed in the Maven test suite |
| `RecoveryMetricsIT` | Integration (Testcontainers) | Passed |
| `ObservabilityEndpointIT` | Integration | Passed |
| `RecoveryFlowIT` (extended) | Integration | Passed |
| `IncidentAutoReopenIT` | Integration | Passed |
| `StaleIncidentRecoveryIT` | Integration | Passed |
| Full backend test suite | Unit + integration | 91 tests, 0 failures, 0 errors, 0 skipped |
| Actuator health and custom Prometheus metrics | Runtime | Verified |
| Dashboard frontend type-check and build | Build | Passed |
| Prometheus scrape and Grafana rendering | Runtime | Not verified. Configuration and dashboard JSON are written; verification steps are in `observability.md`. |

**Verification note:** The backend test suite was executed using `mvn -B clean verify`. The application was also started using Docker Compose, its Actuator health endpoint returned `UP`, and custom CloudRescue metrics were observed at `/actuator/prometheus`. The optional Prometheus/Grafana stack has not yet been verified.

## 9. Deferred
JSON log output, alerting rules and Alertmanager, distributed tracing, authentication on Actuator, persisted availability history, per-region availability, high-volume metric storage in PostgreSQL.
