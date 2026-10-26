# Experiment methodology

## What is measured

All experiment numbers come from a deterministic discrete-time simulation (1 tick = 1 simulated second) of one service
with a primary in Region A and a backup in Region B. It runs the same `RecoveryPolicy` classes as the live system.
It is a model, not real infrastructure.

| Metric | Definition |
|---|---|
| Detection delay | Fault start to incident creation (3 failed probes among the last 5) |
| Recovery time | Detection to verified recovery; only trials that recovered |
| Total time | Fault start to verified recovery |
| Availability | Share of ticks in the 300 s window with a successful probe |
| Success rate | Share of trials reaching verified recovery in the window |
| Duplicate operations | Side effects applied more than once for one logical operation |
| Cost | retry 0.1 + restart 1.0 + failover 5.0 units. **Assumed weights, not measurements** |

## Design

* Each scenario defines fault kind, start (t = 30 s), duration, restart failure probability, acknowledgement-loss
  probability, backup health and a 300 s observation window.
* Trial `i` uses seed `baseSeed + i` for **every** policy. Fault durations are drawn from a separate generator from
  action randomness, so policies face identical faults (common random numbers).
* Action durations are assumptions: retry 1 s, restart 5 s, failover 8 s, plus 1 s verification.
* Results report sample count `n` and sample standard deviation. Recovery time is `n/a`, never 0, when nothing recovered.
* Same seed and settings reproduce identical results.
* A trial counts as recovered only if the last action itself succeeded AND three verification probes pass; a failed action is never credited with a recovery because a later probe happened to pass (Phase 2 rule).

## Scenarios

`TRANSIENT_ERRORS`, `LATENCY_SPIKE`, `SERVICE_CRASH`, `CRASH_RESTART_ALWAYS_FAILS`, `REGION_OUTAGE`, `CRASH_LOST_ACKS`.

## Sample output

One run of the engine (base seed 1000, 30 trials per cell, idempotency on). Re-run it from the Experiments tab; values are
simulation results under the assumptions above, not evidence about real cloud systems.

| Scenario | Policy | Success | Mean recovery (s) | Availability | Cost* |
|---|---|---|---|---|---|
| SERVICE_CRASH | FIXED_RETRY | 0% | n/a | 0.100 | 0.5 |
| SERVICE_CRASH | EXPONENTIAL_BACKOFF | 83% | 55.1 | 0.691 | 2.2 |
| SERVICE_CRASH | COST_AWARE | 100% | 17.3 | 0.936 | 2.7 |
| CRASH_RESTART_ALWAYS_FAILS | EXPONENTIAL_BACKOFF | 0% | n/a | 0.100 | 2.4 |
| CRASH_RESTART_ALWAYS_FAILS | COST_AWARE | 100% | 27.0 | 0.903 | 7.1 |
| REGION_OUTAGE | EXPONENTIAL_BACKOFF | 0% | n/a | 0.500 | 2.4 |
| REGION_OUTAGE | COST_AWARE | 100% | 9.0 | 0.963 | 5.0 |
| TRANSIENT_ERRORS | FIXED_RETRY | 100% | 17.6 | 0.950 | 0.3 |
| TRANSIENT_ERRORS | COST_AWARE | 100% | 17.5 | 0.950 | 1.4 |

Reading it fairly: `COST_AWARE` recovers more often and faster here, but spends more per incident on failover; for
short transient faults the cheap retry-only policy is just as good. Idempotency check (`CRASH_LOST_ACKS`, `COST_AWARE`,
30 trials): 0 duplicate operations with idempotency, 14 without, and slightly slower recovery without it (12.3 s vs 10.5 s).

## Threats to validity

Scenario parameters and action durations are chosen by the author; policies are hand-designed with knowledge of the
scenarios; a single service is simulated; real systems have correlated and gray failures this model does not capture.
