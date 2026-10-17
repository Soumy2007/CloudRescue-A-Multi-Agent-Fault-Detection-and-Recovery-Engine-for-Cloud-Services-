package com.cloudrescue.idempotency;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

public interface IdempotencyRepository extends JpaRepository<IdempotencyRecord, String> {

    /** Atomic claim: exactly one concurrent caller gets 1; the unique key makes every other caller get 0. */
    @Modifying
    @Query(nativeQuery = true, value = "INSERT INTO idempotency_records "
            + "(idem_key, request_hash, status, created_at, updated_at) "
            + "VALUES (:key, :hash, 'IN_PROGRESS', :now, :now) ON CONFLICT (idem_key) DO NOTHING")
    int insertIfAbsent(@Param("key") String key, @Param("hash") String hash, @Param("now") Instant now);

    /** Compare-and-set used to take over an UNKNOWN or stale IN_PROGRESS record. */
    @Modifying
    @Query("update IdempotencyRecord r set r.status = com.cloudrescue.idempotency.IdempotencyStatus.IN_PROGRESS, "
            + "r.updatedAt = :now where r.idemKey = :key and r.status = :from and r.updatedAt = :expected")
    int claim(@Param("key") String key, @Param("from") IdempotencyStatus from,
              @Param("expected") Instant expected, @Param("now") Instant now);
}
