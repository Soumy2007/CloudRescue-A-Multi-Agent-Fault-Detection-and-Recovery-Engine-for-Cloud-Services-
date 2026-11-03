# Observability guide

CloudRescue reports whether recovery succeeded, how long it took, why it failed, and how simulated failures affect
service availability. **All services, regions and failures are simulated.** Nothing here is real infrastructure
monitoring, and availability numbers describe the simulator.

## Endpoints

| URL (default compose, backend on 8081) | What |
|---|---|
| `GET /actuator/health` | Liveness and database health (`UP` / `DOWN`) |
| `GET /actuator/metrics` and `/actuator/metrics/{name}` | Metric browser |
| `GET /actuator/prometheus` | Prometheus text format for scraping |
| `GET /api/observability/summary` | JSON summary used by the dashboard Observability tab |

Only `health`, `info`, `metrics` and `prometheus` are exposed; `env`, `beans`, `heapdump`, `loggers` are not.
**These endpoints have no authentication.** Do not expose the backend port to untrusted networks.

## Metrics

See the full table in [observability-plan.md](observability-plan.md#3-metrics-definitions). Useful PromQL:

```
# recovery success rate over the last hour (finished incidents only)
sum(increase(cloudrescue_incidents_closed_total{state="recovered"}[1h]))
  / sum(increase(cloudrescue_incidents_closed_total[1h]))

# how often actions fail to verify
sum by (outcome) (increase(cloudrescue_recovery_verification_total[15m]))

# p95 detection-to-recovery time (seconds)
histogram_quantile(0.95, sum by (le) (rate(cloudrescue_recovery_duration_seconds_bucket[15m])))
```

Counters restart from 0 when the backend restarts (Prometheus handles this). PostgreSQL remains the durable record.

## Logs

One line per event, `key=value` (logfmt style), after the standard Spring prefix. The prefix also shows `inc=<id> svc=<service>`
from the logging context while the recovery worker handles an incident. Secrets are never logged: keys that look sensitive are redacted,
secret-looking `name=value` text inside values is masked, newlines are removed and long values are cut at 300 characters.

| Event | Emitted by | When |
|---|---|---|
| `incident.detected` | monitor | after the incident row commits |
| `policy.decision` | recovery worker | after the decision commits |
| `recovery.action.completed` | recovery worker | after an action and its verification commit |
| `incident.transition` | incident service | after each state change commits (includes `recovery_duration_s` on RECOVERED) |
| `fault.injected`, `fault.ended` | fault service | after commit |
| `metrics.record_failed` | metrics | first metrics failure (WARN), later ones at debug |
| `recovery.step_failed` | recovery worker | unexpected error (ERROR, with stack trace) |

Illustrative lines (formatted by the real `EventFormat`; the values are made up):

```
event=incident.detected incident_id=42 service=payment-service region=A failure_type=UNAVAILABLE policy=COST_AWARE fault_id=17
event=policy.decision incident_id=42 service=payment-service policy=COST_AWARE failure_type=UNAVAILABLE decision=RESTART delay_ms=0 after_earlier_action=true reason="Still unavailable after retry; restart 1/2"
event=recovery.action.completed incident_id=42 service=payment-service region=A action_id=88 action=RESTART idempotency_key=inc-42-act-2 action_result=success outcome=VERIFIED attempts=1 duration_ms=14
event=recovery.action.completed incident_id=43 service=payment-service region=A action_id=91 action=FAILOVER idempotency_key=inc-43-act-1 action_result=failure outcome=ACTION_FAILED attempts=1 duration_ms=9 failure_reason="backup in region B is unhealthy; failover rejected"
event=incident.transition incident_id=42 service=payment-service from=REMEDIATING to=RECOVERED epoch=0 reason="Verified: RESTART succeeded and the expected node is serving and healthy" recovery_duration_s=5.2
```

Follow one incident: `docker compose logs backend | Select-String "incident_id=42 "` (PowerShell).

## Dashboard

The **Observability** tab (http://localhost:3000) polls `/api/observability/summary` every 2 s and shows total, active, recovered and
failed/escalated incidents, recovery success rate, mean/median/max recovery time, verification failures, incidents by state,
simulated service availability, and recent incidents. It shows "n/a" or "no samples yet" instead of zeros when a value is undefined.

* **Success rate** = recovered / (recovered + failed + escalated); unfinished incidents are excluded.
* **Recovery time** covers automatic recoveries only; an operator re-open includes human waiting time.
* **Availability** = share of the last 300 monitor polls (about 5 minutes at the default 1 s poll) in which the node serving traffic answered.

## Prometheus and Grafana (optional)

```powershell
docker compose -f docker-compose.yml -f docker-compose.monitoring.yml up --build -d
```

* Prometheus: http://localhost:9090, then Status, Targets: job `cloudrescue` must be UP.
* Grafana: http://localhost:3001, user `admin`, password from `GRAFANA_ADMIN_PASSWORD` (default `admin`, change it), dashboard
  CloudRescue, "CloudRescue Recovery".

The core application runs without these. **Status: configuration and dashboard JSON are written; they have not been run by the author.**
Treat the module as verified only after you see the target UP and live panels.

## Verifying everything yourself

```powershell
cd backend; mvn -B clean verify                       # unit + Testcontainers tests (Docker must be running)
cd ..; docker compose up --build -d
# in the dashboard: inject CRASH on a serving node, wait for RECOVERED, then:
Invoke-RestMethod http://localhost:8081/actuator/prometheus | Select-String "cloudrescue_"
Invoke-RestMethod http://localhost:8081/api/observability/summary | ConvertTo-Json -Depth 5
docker compose logs backend --tail 40
```

After one crash recovery with `COST_AWARE` expect: `cloudrescue_incidents_detected_total{failure_type="unavailable"} 1.0`,
`cloudrescue_recovery_verification_total{outcome="action_failed"} 1.0` (the first retry hit the crashed node),
`...{outcome="verified"} 1.0`, `cloudrescue_incidents_closed_total{state="recovered"} 1.0`, a
`cloudrescue_recovery_duration_seconds_count{path="automatic"} 1`, and `cloudrescue_active_incidents 0.0`. Logs, metrics, the incident state
and the dashboard should all agree.

## Limitations

* Metrics and availability samples are in memory and reset on restart; PostgreSQL keeps the incident history.
* Availability is a simulator measurement, not infrastructure monitoring.
* `cloudrescue_incidents_closed_total` counts entries into a final state, so a re-opened incident can be counted twice.
* The summary API reads all incidents; fine for this project's scale, not for millions of rows.
* No alerting rules, tracing, JSON logs or authentication on Actuator.
* One observation can be lost if the process dies between commit and the after-commit callback.
