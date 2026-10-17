package com.cloudrescue;

import com.cloudrescue.common.Errors.IdempotencyKeyReuseException;
import com.cloudrescue.common.Errors.RequestInProgressException;
import com.cloudrescue.idempotency.IdempotencyService;
import com.cloudrescue.simulator.AckLostException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = "cloudrescue.workers.enabled=false")
class IdempotencyServiceIT extends PostgresIT {
    @Autowired IdempotencyService idempotency;

    @Test
    void executesOnceAndReplaysStoredResult() {
        AtomicInteger calls = new AtomicInteger();
        var first = idempotency.execute("k-replay", "h1", () -> { calls.incrementAndGet(); return "OK:done"; }, k -> Optional.empty());
        var second = idempotency.execute("k-replay", "h1", () -> { calls.incrementAndGet(); return "OK:done"; }, k -> Optional.empty());
        assertEquals(1, calls.get());
        assertFalse(first.replayed());
        assertTrue(second.replayed());
        assertEquals("OK:done", second.result());
    }

    @Test
    void rejectsSameKeyWithDifferentPayload() {
        idempotency.execute("k-hash", "h1", () -> "OK:x", k -> Optional.empty());
        assertThrows(IdempotencyKeyReuseException.class,
                () -> idempotency.execute("k-hash", "DIFFERENT", () -> "OK:x", k -> Optional.empty()));
    }

    @Test
    void concurrentRequestsExecuteTheActionExactlyOnce() throws Exception {
        int threads = 8;
        AtomicInteger executions = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        AtomicInteger rejected = new AtomicInteger();
        java.util.List<Future<?>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                try {
                    idempotency.execute("k-concurrent", "h", () -> {
                        executions.incrementAndGet();
                        try { Thread.sleep(300); } catch (InterruptedException ignored) { }
                        return "OK:once";
                    }, k -> Optional.empty());
                } catch (RequestInProgressException e) {
                    rejected.incrementAndGet();
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : futures) f.get(10, TimeUnit.SECONDS);
        pool.shutdown();
        assertEquals(1, executions.get(), "side effect must run exactly once");
        assertEquals(threads - 1, rejected.get());
    }

    @Test
    void ambiguousOutcomeIsReconciledInsteadOfReExecuted() {
        AtomicInteger effects = new AtomicInteger();
        Map<String, String> downstream = new ConcurrentHashMap<>();
        // First attempt: effect happens, then the acknowledgement is lost.
        assertThrows(AckLostException.class, () -> idempotency.execute("k-ambiguous", "h", () -> {
            effects.incrementAndGet();
            downstream.put("k-ambiguous", "OK:restarted");
            throw new AckLostException("k-ambiguous");
        }, k -> Optional.ofNullable(downstream.get(k))));
        // Retry with the same key: reconciliation finds the effect, the action is NOT run again.
        var retry = idempotency.execute("k-ambiguous", "h", () -> { effects.incrementAndGet(); return "OK:restarted"; },
                k -> Optional.ofNullable(downstream.get(k)));
        assertEquals(1, effects.get());
        assertTrue(retry.replayed());
        assertEquals("OK:restarted", retry.result());
    }

    @Test
    void ambiguousOutcomeWithNoEffectIsSafelyReExecuted() {
        AtomicInteger effects = new AtomicInteger();
        assertThrows(IllegalStateException.class, () -> idempotency.execute("k-noeffect", "h",
                () -> { throw new IllegalStateException("crashed before effect"); }, k -> Optional.empty()));
        var retry = idempotency.execute("k-noeffect", "h", () -> { effects.incrementAndGet(); return "OK:ran"; }, k -> Optional.empty());
        assertEquals(1, effects.get());
        assertFalse(retry.replayed());
    }
}
