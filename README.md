# CloudRescue

**Failure-aware cloud workload recovery manager.** CloudRescue watches simulated cloud services in two logical
regions, detects failures, opens incidents, picks a recovery strategy (retry, restart, or failover), executes it
safely with idempotency, verifies recovery, and measures which policy works best.

> **The cloud is simulated.** Services, regions and failures are controlled in-process. Detection, the incident
> state machine, policy logic, idempotency, PostgreSQL persistence and the experiment analytics are real code.
> Nothing here performs real multi-region failover.

## Run it (Docker only)

```bash
docker compose up --build
```

| What | URL |
|---|---|
| Dashboard | http://localhost:3000 |
| REST API | http://localhost:8081/api/services |
| Swagger / OpenAPI | http://localhost:8081/swagger-ui.html |
| Health | http://localhost:8081/actuator/health |

First build downloads Maven and npm dependencies, so allow a few minutes. Stop with `Ctrl+C`; wipe the database with
`docker compose down -v`. Copy `.env.example` to `.env` to change passwords or the default policy.

## 2-minute demo

1. **Overview tab.** Six nodes appear: `payment`, `order`, `auth` in Region A (serving) and Region B (standby).
   Each row has a heartbeat strip of the last 30 probes.
2. **Crash.** Inject `CRASH` on `payment-service @ A`. About 3 seconds later an incident opens. Watch the strip go red,
   then the policy retries once, restarts the service, verifies health, and the incident reaches `RECOVERED`.
3. **Region outage.** Click *Reset all faults*, then inject `REGION_OUTAGE` on region A. All three services fail
   together, are classified `REGION_SUSPECTED`, and the `COST_AWARE` policy fails them over to Region B.
4. **Compare policies live.** Switch the policy dropdown to `FIXED_RETRY`, inject a `CRASH`, and watch it retry
   five times and end `FAILED`. Open the incident and press *Re-open & restart* (operator path).
5. **Lost acknowledgement.** Inject `ACK_LOSS` (severity 1.0) and then `CRASH` on one node. The restart happens,
   its acknowledgement is "lost", and the retry is *reconciled* instead of restarting twice. See "replayed stored
   result" on the action row and `duplicateEffects = 0`.
6. **Experiments tab.** Run the experiment: 6 scenarios x 3 policies x 30 trials on a virtual clock in about a second.

## Architecture (modular monolith)

```
Fault Injector -> Simulator (Region A / B nodes)
                       | probes
                 Monitor -- 3 of last 5 probes failed --> Incident Manager (state machine, PostgreSQL)
                                                                 |
                                         Recovery Worker --> Policy (decides) --> Remediation Executor (acts)
                                                                                    |  via Idempotency module
                                                                                    v
                                                          verify (3 probes) --> RECOVERED / re-analyze / FAILED / ESCALATED
Experiment Runner (virtual clock, same policies) --> Analytics --> REST API --> React dashboard
```

| Package | Responsibility |
|---|---|
| `simulator` | In-memory nodes, health probes, restart, effect log |
| `fault` | Fault injection records, expiry, reset, dev/test gate |
| `monitor` | Polling, rolling window detection, failure classification |
| `incident` | Incident entity, validated state machine, transition audit, optimistic locking |
| `policy` | `RecoveryPolicy` interface, 3 policies, decision audit. Pure logic, no I/O |
| `remediation` | Executor (retry/restart/failover) and the recovery worker loop |
| `idempotency` | Durable keys, request hash, concurrency claim, reconciliation |
| `experiment` | Deterministic simulation engine and trial persistence |
| `analytics` | Means, sample standard deviations, live stats, metric definitions |
| `api` | Thin controllers, DTOs, consistent errors |

More detail: [docs/architecture.md](docs/architecture.md), [docs/api.md](docs/api.md),
[docs/experiment-methodology.md](docs/experiment-methodology.md), [docs/recovery-rules.md](docs/recovery-rules.md).

## Incident state machine

```
DETECTED -> ANALYZING -> REMEDIATING -> RECOVERED
              |  ^           |  |
              |  +-----------+  +--> FAILED  --(operator re-open)--> ANALYZING
              +--> ESCALATED --(operator re-open)--> ANALYZING
              +--> FAILED   (policy gives up before scheduling anything)
```

Invalid transitions throw `InvalidTransitionException` (HTTP 409). Each transition and its audit row commit in one transaction. `@Version` optimistic locking prevents conflicting updates. Each recovery step also runs in one transaction that first takes a pessimistic row lock on the incident (`SELECT ... FOR UPDATE`), so two workers cannot step the same incident at the same time.

A partial unique index (`uq_one_unresolved_incident_per_service`) blocks duplicate incidents at the database level.

## Idempotency (and its honest limits)

A unique key alone does **not** give exactly-once execution. If the acknowledgement is lost after the effect happened,
the caller cannot tell. CloudRescue handles this by:

1. Claiming the key atomically (`INSERT ... ON CONFLICT DO NOTHING`).
2. Replaying the stored result for repeats; rejecting the same key with a different request hash (409).
3. Marking ambiguous runs `UNKNOWN` and **reconciling** against the downstream effect log before ever executing again.
4. Taking over `UNKNOWN`/stale claims with a compare-and-set so only one re-executor wins.

Retried remediation actions reuse the same key (`inc-<incidentId>-act-<seq>`).

## Tests

```bash
cd backend && mvn verify      # needs Docker running (Testcontainers starts PostgreSQL)
cd frontend && npm install && npm run build
```

* Unit: policies, state machine, simulation engine (determinism, detection delay, idempotency effect).
* Integration (Testcontainers + PostgreSQL): idempotency under 8 concurrent threads, hash-mismatch rejection,
  ambiguous-outcome reconciliation, valid/invalid transitions, concurrent conflicting transitions, duplicate-incident
  prevention, re-open rules, and end-to-end crash / region-outage / lost-ack recovery.

## Verification status

- **Backend (local):** `mvn -B verify` on 2026-10-26 finished with BUILD SUCCESS: 47 tests run, 0 failures, 0 errors, 0 skipped. Testcontainers started PostgreSQL 16 and Flyway applied all 3 migrations to an empty database.
- **Recovery correctness (local):** `RecoveryCorrectnessIT` passed 10/10 and `RecoveryFlowIT` passed 3/3.
- **Docker Compose (local):** backend, frontend and PostgreSQL containers ran together, and the demo scenarios (crash, region outage, lost acknowledgement) were exercised through the dashboard.
- **GitHub Actions:** not yet verified.

## Limitations

* Simulated regions, services and failures; no real traffic switching.
* Single application instance. Multi-instance safety relies on optimistic locking, the unique action sequence and the
  idempotency claim, but it has not been load- or chaos-tested.
* Failure classification (region vs service) is a heuristic over probe results.
* Overlapping faults on the same node follow last-writer-wins in the simulator. No automatic failback after failover.
* A crashed standby node is not repaired automatically (no incident is opened for it) and blocks failover for that service.
* Experiment cost values are assumptions. Experiment duplicates use an in-memory model of idempotency; the real
  database-backed implementation is covered by the integration tests instead.
* Fault injection is gated by `FAULT_INJECTION_ENABLED`. There is no authentication; do not expose this to the internet.

## Ideas for extensions (not built)
Rolling-window anomaly detection (z-score), a human-approved LLM incident summary, RabbitMQ between components, Prometheus/Grafana metrics, Resilience4j circuit breakers, server-sent events for the dashboard.
