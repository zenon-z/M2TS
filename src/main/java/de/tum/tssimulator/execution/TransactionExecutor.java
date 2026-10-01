package de.tum.tssimulator.execution;

import de.tum.tssimulator.bookkeeping.BookkeeperTransaction;
import de.tum.tssimulator.config.ConcurrencyModel;
import de.tum.tssimulator.core.ExecutionPhase;
import de.tum.tssimulator.core.Transaction;
import de.tum.tssimulator.lifecycle.TransactionLifecyclePort;
import de.tum.tssimulator.state.TransactionGroupRegistry;

/**
 * Facade for transaction execution.
 * Delegates to mode-specific executor strategy (ACID vs BASE).
 * 
 * This class provides a unified interface for executing transactions while
 * hiding the complexity of different execution modes and group coordination.
 */
public class TransactionExecutor {
    private final TransactionExecutionStrategy strategy;
    
    // Legacy fields for backward compatibility
    private final TransactionProcessor processor;
    private final TransactionLifecyclePort lifecycle;
    private final ConcurrencyModel concurrencyModel;

    /**
     * Create executor with explicit strategy (recommended).
     * 
     * @param strategy Execution strategy (ACID or BASE)
     */
    public TransactionExecutor(TransactionExecutionStrategy strategy) {
        if (strategy == null) {
            throw new IllegalArgumentException("TransactionExecutionStrategy cannot be null");
        }
        this.strategy = strategy;
        this.processor = null;
        this.lifecycle = null;
        this.concurrencyModel = strategy.getMode();
    }
    
    /**
     * Create executor with processor and mode (backward compatibility).
     * 
     * @param processor Transaction processor
     * @param lifecycle Lifecycle manager
     * @param concurrencyModel ACID or BASE mode
     * @deprecated This constructor is disabled. Use TransactionExecutor(TransactionExecutionStrategy) instead.
     */
    @Deprecated(forRemoval = true)
    public TransactionExecutor(TransactionProcessor processor, 
                              TransactionLifecyclePort lifecycle,
                              ConcurrencyModel concurrencyModel) {
        throw new UnsupportedOperationException(
            "Deprecated TransactionExecutor constructor is disabled. " +
            "Use TransactionExecutor(TransactionExecutionStrategy) instead.");
    }

    /**
     * Execute transaction to completion using mode-specific semantics.
     * Automatically handles simple transactions and group coordination.
     * 
     * @param txn Transaction to execute
     * @return true if transaction completed successfully
     */
    public boolean execute(Transaction txn) {
        return strategy.execute(txn);
    }
    
    /**
     * Complete an already-processed transaction (for use by phase callbacks).
     * Transaction must already be at COMPLETE phase after going through LOCK->IO->CPU.
     * 
     * @param txn Transaction to complete
     * @return true if completion was successful
     */
    public boolean complete(Transaction txn) {
        return strategy.complete(txn);
    }
    
    /**
     * Get the concurrency mode.
     * 
     * @return ConcurrencyModel (ACID or BASE)
     */
    public ConcurrencyModel getMode() {
        return concurrencyModel;
    }

    /**
     * Execute a simple transaction (no group coordination).
     * @deprecated Use execute(Transaction) instead
     */
    @Deprecated
    public boolean executeTransaction(Transaction txn) {
        boolean success = processor.process(txn);
        if (!success) return false;

        processor.release(txn);
        
        // Transaction should already be at COMPLETE phase after processing
        if (txn.getPhase() != ExecutionPhase.COMPLETE) {
            throw new IllegalStateException(
                "Transaction " + txn.getId() + " not at COMPLETE phase after processing. " +
                "Current phase: " + txn.getPhase() + ". This indicates a bug in phase execution.");
        }
        
        lifecycle.complete(txn);

        return true;
    }

    /**
     * Execute transaction with group coordination (parent + bookkeepers).
     * @deprecated Use execute(Transaction) instead
     */
    @Deprecated
    public boolean executeTransactionWithGroup(Transaction txn,
                                               BookkeeperOrchestrator bkOrchestrator,
                                               TransactionGroupRegistry registry) {
        TransactionGroupCompletionHandler handler = 
            new TransactionGroupCompletionHandler(processor, lifecycle, registry, concurrencyModel);

        if (txn instanceof BookkeeperTransaction) {
            return handler.completeBookkeeper((BookkeeperTransaction) txn);
        } else {
            return handler.completeTransaction(txn, bkOrchestrator);
        }
    }

}
