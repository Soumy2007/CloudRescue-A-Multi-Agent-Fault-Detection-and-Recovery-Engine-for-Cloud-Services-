package com.cloudrescue.idempotency;

/** IN_PROGRESS: claimed. COMPLETED/FAILED: definitive result stored. UNKNOWN: outcome ambiguous, must reconcile. */
public enum IdempotencyStatus { IN_PROGRESS, COMPLETED, FAILED, UNKNOWN }
