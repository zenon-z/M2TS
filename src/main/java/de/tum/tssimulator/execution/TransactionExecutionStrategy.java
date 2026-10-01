package de.tum.tssimulator.execution;

import de.tum.tssimulator.config.ConcurrencyModel;
import de.tum.tssimulator.core.Transaction;

/**
 * Strategy for executing transactions with mode-specific semantics (ACID vs BASE).
 * 
 * Implementations provide different execution strategies:
 * - ACID: Atomic group completion with strong consistency
 * - BASE: Eventual consistency with immediate parent completion
 * 
 * This interface allows the system to switch between execution modes
 * without changing the caller's code.
 */
public interface TransactionExecutionStrategy {
    /**
     * Execute a transaction to completion with mode-specific semantics.
     * Automatically handles simple transactions and group coordination.
     * 
     * @param txn Transaction to execute
     * @return true if transaction completed successfully
     */
    boolean execute(Transaction txn);
    
    /**
     * Complete an already-processed transaction (for CPU callbacks).
     * Handles bookkeeper creation and mode-specific completion without re-processing.
     * 
     * @param txn Transaction to complete
     * @return true if transaction completed successfully
     */
    boolean complete(Transaction txn);
    
    /**
     * Get the concurrency mode of this strategy.
     * 
     * @return ConcurrencyModel (ACID or BASE)
     */
    ConcurrencyModel getMode();
}
