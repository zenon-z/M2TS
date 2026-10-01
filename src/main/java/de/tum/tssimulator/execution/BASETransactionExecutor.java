package de.tum.tssimulator.execution;

import de.tum.tssimulator.bookkeeping.BookkeeperTransaction;
import de.tum.tssimulator.config.ConcurrencyModel;
import de.tum.tssimulator.core.ExecutionPhase;
import de.tum.tssimulator.core.Transaction;
import de.tum.tssimulator.lifecycle.TransactionLifecyclePort;
import de.tum.tssimulator.state.DirtyStateTracker;
import de.tum.tssimulator.state.TransactionGroupRegistry;

/**
 * BASE execution strategy - eventual consistency.
 * 
 * Parent completes immediately, bookkeepers run asynchronously in background.
 * Provides better performance at the cost of eventual consistency instead of
 * strong consistency.
 * 
 * Execution flow:
 * 1. Process transaction (acquire locks, execute phases)
 * 2. Validate transaction reached COMPLETE phase
 * 3. Route to appropriate completion handler (parent vs bookkeeper)
 * 4. Completion handler completes immediately (no waiting)
 */
public class BASETransactionExecutor implements TransactionExecutionStrategy {
    private final TransactionProcessor processor;
    private final TransactionLifecyclePort lifecycle;
    private final TransactionGroupRegistry registry;
    private final BookkeeperOrchestrator orchestrator;
    private final BASECompletionHandler completionHandler;

    public BASETransactionExecutor(TransactionProcessor processor,
                                  TransactionLifecyclePort lifecycle,
                                  TransactionGroupRegistry registry,
                                  BookkeeperOrchestrator orchestrator,
                                  DirtyStateTracker dirtySetManager) {
        this.processor = processor;
        this.lifecycle = lifecycle;
        this.registry = registry;
        this.orchestrator = orchestrator;
        
        // Create completion handler with services from processor
        this.completionHandler = new BASECompletionHandler(
            processor.getLockingService(),
            processor.getBookkeeperService(),
            lifecycle,
            dirtySetManager,
            registry
        );
    }

    @Override
    public boolean execute(Transaction txn) {
        // Process transaction (locking + execution)
        boolean success = processor.process(txn);
        if (!success) return false;

        // Delegate to completion
        return complete(txn);
    }

    @Override
    public boolean complete(Transaction txn) {
        // Validate COMPLETE phase
        if (txn.getPhase() != ExecutionPhase.COMPLETE) {
            throw new IllegalStateException(
                "Transaction " + txn.getId() + " not at COMPLETE phase. " +
                "Current phase: " + txn.getPhase());
        }

        // Route based on transaction type
        if (txn instanceof BookkeeperTransaction) {
            return completionHandler.completeBookkeeper((BookkeeperTransaction) txn);
        } else {
            return completionHandler.completeParent(txn, orchestrator);
        }
    }

    @Override
    public ConcurrencyModel getMode() {
        return ConcurrencyModel.BASE;
    }
}
