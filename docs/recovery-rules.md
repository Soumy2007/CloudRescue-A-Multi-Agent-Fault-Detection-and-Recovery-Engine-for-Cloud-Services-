# Recovery-state rules (Phase 2)

## The rule

An incident becomes `RECOVERED` only if **all three** hold after a remediation action (`RecoveryRules.evaluate`):

1. the action itself **succeeded**;
2. the node now serving traffic is the node the action was **expected** to leave serving
   (retry / restart: the node acted on; failover: the intended backup);
3. that node answers **3 consecutive** health probes.

| Outcome | Meaning | Incident goes to |
|---|---|---|
| `VERIFIED` | all three hold | `RECOVERED` |
| `ACTION_FAILED` | the action failed (even if the service now answers) | `ANALYZING` (policy decides again) |
| `POSTCONDITION_FAILED` | action reported success but expected state not observed | `ANALYZING` |

Before this change the worker marked an incident `RECOVERED` whenever whichever node was active answered probes,
ignoring whether the action failed. Example of the bug: failover rejected because the backup is down, primary happens
to be healthy, incident wrongly recovered.

## How each action is checked

| Action | Precondition | Success | Expected node after |
|---|---|---|---|
| RETRY | target is still serving traffic | health probe passes | target |
| RESTART | target is serving; region is up | simulator restarts it (a region outage makes it fail) | target |
| FAILOVER | source serving; backup healthy | traffic moved to the backup | the backup chosen up front |

`target_node_id` and `expected_active_node_id` are stored on the action **before** execution (migration `V3`), so
retries, replays and crash-resume all use the same targets and the same idempotency request hash.

## Why a restart cannot fix a region outage
`SimulatorService.restart` fails while the node's region is down and records no effect. The action is `ACTION_FAILED`,
so the incident cannot recover through restarts; only failover (or the region coming back and a later successful action)
can. Covered by `SimulatorServiceTest` and `RecoveryCorrectnessIT`.

   ## Idempotency and concurrency (unchanged guarantees, plus row locking)
   * Same key + same payload hash: stored result replayed. Different hash: rejected (409). Ambiguous outcome: reconciled.
   * Each recovery step runs in one database transaction that first locks the incident row (`SELECT ... FOR UPDATE`), so two workers cannot step the same incident at once. Transitions and remediation actions also use optimistic locking (`@Version`).
   * A step's database writes commit or roll back together. The simulator's effect log and the idempotency records commit separately, so after a rollback a re-run reconciles through the idempotency key, and the post-condition check catches any mismatch.
   * Policy selection (`RecoveryPolicy`) is untouched and still separate from execution (`RemediationService`).

   ## Unresolved edge cases
   1. **Self-healed after a failed action.** If a fault expires between a failed action and verification, the failed action is not credited; the policy simply chooses the next action. This costs one extra action (for `COST_AWARE` on an `UNAVAILABLE` incident that can be a restart of an already-healthy node). Chosen deliberately: recovery is credited only to an action that worked.
   2. **Verification is 3 immediate probes, not a sustained window.** A flapping fault (high error rate) could pass by chance.
   3. **"Exactly one active node per service" is enforced by application logic and verification, not a database constraint.** A partial unique index would break bulk updates (non-deferrable); a trigger or exclusion approach is future work.
   4. **No incidents for standby nodes** and **no automatic failback** (see README limitations).
   5. **Probabilistic simulator with one shared RNG:** live runs are not bit-for-bit reproducible; the experiment engine is.
   6. **The incident row lock has no timeout**, so a step that hangs would hold the lock.
   7. **Connection use.** A step holds one database connection for its transaction while the idempotency module uses a second (`REQUIRES_NEW`). This is safe with the default single scheduler thread; with many worker threads the connection pool must be larger than twice the thread count.
   8. **Test status.** `mvn -B clean verify` on 2026-10-26: 47 tests, 0 failures, 0 errors, against real PostgreSQL (Testcontainers). The GitHub Actions run has not been verified yet.