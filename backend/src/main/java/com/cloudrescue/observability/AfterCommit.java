package com.cloudrescue.observability;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Runs an observation (metric increment or log line) only AFTER the surrounding transaction has committed.
 *
 * Recovery steps run inside a database transaction that can roll back and be re-run (for example after an
 * optimistic-lock conflict). Recording inside the transaction would count that step twice. With no active
 * transaction the observation runs immediately. An observation that throws never affects the caller.
 */
public final class AfterCommit {
    private static final Logger log = LoggerFactory.getLogger(AfterCommit.class);

    private AfterCommit() {}

    public static void run(Runnable observation) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    safely(observation);
                }
            });
        } else {
            safely(observation);
        }
    }

    private static void safely(Runnable r) {
        try {
            r.run();
        } catch (RuntimeException e) {
            log.debug("Observation failed and was ignored: {}", e.toString());
        }
    }
}
