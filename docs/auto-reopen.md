# Stale incident auto re-open

## The problem this fixes
CloudRescue allows only one unresolved incident per service (a partial unique index in PostgreSQL). `FAILED` and
`ESCALATED` count as unresolved, so an old failed incident used to block detection of every later failure of that
service: a new crash was seen by the monitor but no incident was opened, and nothing recovered it until an operator
re-opened the old incident by hand.

## The rule
When the monitor sees the node serving a service fail (3 of the last 5 probes) and the blocking incident is
`FAILED` or `ESCALATED`, it re-opens that incident (`FAILED/ESCALATED -> ANALYZING`) if both limits allow it:

| Limit | Default | Setting |
|---|---|---|
| The incident has been in its final state for at least | 30 s | `cloudrescue.recovery.auto-reopen.min-age-ms` (`AUTO_REOPEN_MIN_AGE_MS`) |
| The incident's `epoch` is below | 3 | `cloudrescue.recovery.auto-reopen.max-epoch` (`AUTO_REOPEN_MAX_EPOCH`) |

On re-open the incident:
* gets a new epoch, so the policy's action counters start again;
* adopts the policy currently selected in the dashboard and the failure type observed now;
* is logged as `event=incident.transition ... reason="Auto re-opened: ..."` and `event=incident.auto_reopened`.

## What does not change
* Recovery still needs a successful action and a verified expected post-state (Phase 2). Re-opening never marks
  anything recovered.
* Active incidents (`DETECTED`, `ANALYZING`, `REMEDIATING`) are left alone.
* A service that is healthy keeps its old `FAILED` incident; it is only re-opened when the service fails again.

## Limits and honest caveats
* Operator re-opens also raise the epoch, so they use up the same budget of 3.
* After the limit, an unrepairable service stays `FAILED` until an operator re-opens it from the dashboard.
* With `FIXED_RETRY` selected, each automatic attempt is only 5 retries; select a stronger policy for incidents that need a restart or failover.
* A stale `FAILED` incident whose service is healthy still counts in "active incidents". Closing it automatically is not implemented.
* The default 30 s `min-age` means a crash right after an incident failed waits up to 30 s before the automatic retry.
