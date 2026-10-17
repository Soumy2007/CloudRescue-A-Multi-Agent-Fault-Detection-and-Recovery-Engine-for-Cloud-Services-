# REST API

Interactive docs: `http://localhost:8080/swagger-ui.html`. Errors always use
`{ timestamp, status, error, message, path }`.

| Method & path | Purpose | Notes |
|---|---|---|
| `GET /api/services` | Node health, heartbeat history | Polled by the dashboard |
| `GET /api/incidents?state=&page=&size=` | Paged incidents | `size` capped at 100 |
| `GET /api/incidents/{id}` | Incident, transitions, actions, policy decisions | 404 if unknown |
| `POST /api/incidents/{id}/reopen` | Operator re-open of `FAILED`/`ESCALATED` | Body `{"action":"RESTART"}` optional; 409 otherwise |
| `GET /api/faults` | Recent fault injections | |
| `POST /api/faults` | Inject a fault | 201; 403 when disabled; 400 on bad input |
| `POST /api/faults/reset` | End all active faults | |
| `GET /api/policies` | Policies and which is active | |
| `PUT /api/policies/active` | Choose policy for NEW incidents | `{"name":"COST_AWARE"}` |
| `GET /api/experiments/scenarios` | Built-in scenarios | |
| `POST /api/experiments` | Run an experiment batch | Body optional: `trials` (1-200), `seed`, `scenarios`, `policies`, `idempotencyEnabled` |
| `GET /api/experiments` | List batches | |
| `GET /api/experiments/{batchId}/summary` | Per-cell and overall statistics | |
| `GET /api/analytics/live` | Live incident counts and policy stats | |
| `GET /api/analytics/metrics` | Metric definitions | |

Fault body example:

```json
{ "type": "CRASH", "nodeId": 1 }
{ "type": "ERROR_RATE", "nodeId": 3, "severity": 0.8, "durationSec": 30 }
{ "type": "REGION_OUTAGE", "region": "A", "durationSec": 60 }
```

Node ids come from `GET /api/services`.
