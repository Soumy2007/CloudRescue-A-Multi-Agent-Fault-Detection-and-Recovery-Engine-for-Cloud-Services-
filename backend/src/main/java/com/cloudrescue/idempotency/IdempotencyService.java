package com.cloudrescue.idempotency;

import com.cloudrescue.common.Errors.IdempotencyKeyReuseException;
import com.cloudrescue.common.Errors.RequestInProgressException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Idempotent execution of side-effecting operations.
 *
 * A unique key alone does NOT give exactly-once execution: if the process dies, or the acknowledgement is
 * lost, after the effect but before the result is stored, the outcome is ambiguous. This service therefore:
 *  1. claims the key atomically (INSERT ... ON CONFLICT DO NOTHING);
 *  2. returns the stored result for repeats, and rejects the same key with a different payload hash;
 *  3. marks ambiguous executions UNKNOWN and RECONCILES them against the downstream system
 *     (did the effect happen?) before ever executing again;
 *  4. takes over UNKNOWN/stale records with a compare-and-set so only one re-executor wins.
 * Every state change commits in its own transaction, independent of the caller's.
 */
@Service
public class IdempotencyService {

    public record Outcome(String result, boolean replayed) {}

    private final IdempotencyRepository repo;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final long staleAfterMs;

    public IdempotencyService(IdempotencyRepository repo, PlatformTransactionManager txManager, Clock clock,
                              @Value("${cloudrescue.idempotency.stale-after-ms:10000}") long staleAfterMs) {
        this.repo = repo;
        this.tx = new TransactionTemplate(txManager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.clock = clock;
        this.staleAfterMs = staleAfterMs;
    }

    /**
     * @param action     performs the side effect and returns "OK:..." or "FAIL:..."; may throw if the outcome is unknown
     * @param reconciler asks the downstream system whether the effect for this key was already applied
     */
    public Outcome execute(String key, String requestHash, Supplier<String> action,
                           Function<String, Optional<String>> reconciler) {
        Instant now = clock.instant();
        Integer inserted = tx.execute(s -> repo.insertIfAbsent(key, requestHash, now));
        if (inserted != null && inserted == 1) {
            return runClaimed(key, action);
        }

        IdempotencyRecord rec = repo.findById(key).orElseThrow();
        if (!rec.requestHash.equals(requestHash)) {
            throw new IdempotencyKeyReuseException(key);
        }
        switch (rec.status) {
            case COMPLETED, FAILED:
                return new Outcome(rec.result, true);
            case IN_PROGRESS:
                if (Duration.between(rec.updatedAt, now).toMillis() < staleAfterMs) {
                    throw new RequestInProgressException(key);
                }
                break; // stale claim: the owner probably died, treat the outcome as unknown
            default:
                break;
        }

        Optional<String> applied = reconciler.apply(key);
        if (applied.isPresent()) {
            finish(key, applied.get());
            return new Outcome(applied.get(), true);
        }
        Integer claimed = tx.execute(s -> repo.claim(key, rec.status, rec.updatedAt, clock.instant()));
        if (claimed == null || claimed == 0) {
            throw new RequestInProgressException(key);
        }
        return runClaimed(key, action);
    }

    private Outcome runClaimed(String key, Supplier<String> action) {
        String result;
        try {
            result = action.get();
        } catch (RuntimeException e) {
            markUnknown(key);
            throw e;
        }
        finish(key, result);
        return new Outcome(result, false);
    }

    private void finish(String key, String result) {
        tx.executeWithoutResult(s -> {
            IdempotencyRecord r = repo.findById(key).orElseThrow();
            r.status = result.startsWith("FAIL:") ? IdempotencyStatus.FAILED : IdempotencyStatus.COMPLETED;
            r.result = result.length() > 500 ? result.substring(0, 500) : result;
            r.updatedAt = clock.instant();
            r.completedAt = r.updatedAt;
            repo.save(r);
        });
    }

    private void markUnknown(String key) {
        tx.executeWithoutResult(s -> {
            IdempotencyRecord r = repo.findById(key).orElseThrow();
            r.status = IdempotencyStatus.UNKNOWN;
            r.updatedAt = clock.instant();
            repo.save(r);
        });
    }

    public Optional<IdempotencyRecord> find(String key) {
        return repo.findById(key);
    }
}
