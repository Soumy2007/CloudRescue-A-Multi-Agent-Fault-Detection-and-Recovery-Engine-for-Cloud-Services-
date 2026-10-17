package com.cloudrescue.idempotency;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "idempotency_records")
public class IdempotencyRecord {
    @Id
    public String idemKey;
    public String requestHash;
    @Enumerated(EnumType.STRING)
    public IdempotencyStatus status;
    public String result;
    public Instant createdAt;
    public Instant updatedAt;
    public Instant completedAt;
}
