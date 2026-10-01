package de.tum.tssimulator.execution;

import de.tum.tssimulator.core.Transaction;

/**
 * Strategy interface for different concurrency control models (ACID, BASE, etc.)
 */
public interface ConcurrencyStrategy {
    /**
     * Process a transaction according to the concurrency model.
     * For ACID: Acquire locks first, return false if denied.
     * For BASE: Record write optimistically, always return true.
     * 
     * @param txn Transaction to process
     * @param virtualTime Current simulation time
     * @return true if transaction can proceed to execution phase
     */
    boolean process(Transaction txn);
    
    /**
     * Release resources after transaction completes.
     * For ACID: Release locks.
     * For BASE: No-op (no locks held).
     */
    void release(Transaction txn);
    
    /**
     * Called periodically for strategy-specific maintenance.
     * For ACID: No-op.
     * For BASE: Perform reconciliation.
     * 
     * @param virtualTime Current simulation time
     */
    void periodicMaintenance(long virtualTime);
    
    /**
     * Get the name of this concurrency strategy for logging.
     */
    String getName();
}
