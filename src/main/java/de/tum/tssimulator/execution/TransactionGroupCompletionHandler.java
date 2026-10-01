package de.tum.tssimulator.execution;

import de.tum.tssimulator.bookkeeping.BookkeeperTransaction;
import de.tum.tssimulator.config.ConcurrencyModel;
import de.tum.tssimulator.core.Transaction;
import de.tum.tssimulator.lifecycle.TransactionLifecyclePort;
import de.tum.tssimulator.state.TransactionGroupRegistry;

/**
 * Manages transaction group completion logic.
 * Extracted from TransactionExecutor to follow Single Responsibility Principle.
 * 
 * Responsibilities:
 * - Coordinate parent and bookkeeper completion
 * - Determine when a group is fully complete
 * - Handle group synchronization edge cases
 * 
 * BASE vs ACID completion semantics:
 * - ACID: Parent waits for all bookkeepers to complete (atomic group)
 * - BASE: Parent completes immediately, bookkeepers run asynchronously (eventual consistency)
 */
class TransactionGroupCompletionHandler {
    private final GroupCompletionPolicy policy;

    public TransactionGroupCompletionHandler(TransactionProcessor processor,
                                            TransactionLifecyclePort lifecycle,
                                            TransactionGroupRegistry registry,
                                            ConcurrencyModel concurrencyModel) {
        this.policy = concurrencyModel == ConcurrencyModel.BASE
                ? new BaseGroupCompletionPolicy(processor, lifecycle, registry)
                : new AcidGroupCompletionPolicy(processor, lifecycle, registry);
    }

    /**
     * Process bookkeeper completion after CPU phase.
     * 
     * Bookkeepers can create their own bookkeepers (cascading); the cascade terminates via
     * BookkeeperCreationCoordinator's already-covered-resources visited set, not a depth limit.
     * This method creates child bookkeepers before completing the current bookkeeper.
     * 
     * BASE semantics: Each bookkeeper completes independently.
     * ACID semantics: Wait for all bookkeepers, then complete group atomically.
     */
    public boolean completeBookkeeper(BookkeeperTransaction bookkeeper) {
        return policy.completeBookkeeper(bookkeeper);
    }

    /**
     * Process parent transaction completion after CPU phase.
     * 
     * This is the bookkeeper creation trigger point for BOTH ACID and BASE modes.
     * Parent must have reached COMPLETE phase before this method is called.
     * 
     * Flow:
     * 1. Verify parent is at COMPLETE phase
     * 2. Create and queue bookkeepers via processor.createAndQueueBookkeepers()
     * 3. Apply mode-specific completion logic:
     *    - BASE: Complete parent immediately (bookkeepers run async)
     *    - ACID: Move parent to waiting room (wait for bookkeepers)
     * 
     * @param parent The parent transaction that finished CPU phase
     * @param orchestrator For ON_RESTRICT enforcement
     * @return true if parent completed, false if waiting for bookkeepers
     */
    public boolean completeTransaction(Transaction parent, BookkeeperOrchestrator orchestrator) {
        return policy.completeTransaction(parent, orchestrator);
    }
}
