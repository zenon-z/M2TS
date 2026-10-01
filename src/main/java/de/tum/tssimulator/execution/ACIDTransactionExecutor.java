package de.tum.tssimulator.execution;

import de.tum.tssimulator.bookkeeping.BookkeeperTransaction;
import de.tum.tssimulator.config.ConcurrencyModel;
import de.tum.tssimulator.core.ExecutionPhase;
import de.tum.tssimulator.core.Transaction;
import de.tum.tssimulator.execution.init.TransactionPhaseInitializer;
import de.tum.tssimulator.lifecycle.TransactionLifecyclePort;
import de.tum.tssimulator.state.TransactionGroupRegistry;

/**
 * ACID execution strategy - atomic group completion.
 * 
 * Parent waits for all bookkeepers before completing as atomic unit.
 * Provides strong consistency and ACID guarantees.
 * 
 * Execution flow:
 * 1. Process transaction (acquire locks, execute phases)
 * 2. Validate transaction reached COMPLETE phase
 * 3. Route to appropriate completion handler (parent vs bookkeeper)
 * 4. Completion handler coordinates group atomicity
 */
public class ACIDTransactionExecutor implements TransactionExecutionStrategy {
    private final TransactionProcessor processor;
    private final TransactionLifecyclePort lifecycle;
    private final TransactionGroupRegistry registry;
    private final BookkeeperOrchestrator orchestrator;
    private final ACIDCompletionHandler completionHandler;

    public ACIDTransactionExecutor(TransactionProcessor processor,
                                   TransactionLifecyclePort lifecycle,
                                   TransactionGroupRegistry registry,
                                   BookkeeperOrchestrator orchestrator) {
        this.processor = processor;
        this.lifecycle = lifecycle;
        this.registry = registry;
        this.orchestrator = orchestrator;
        
        // Create completion handler with services from processor
        this.completionHandler = new ACIDCompletionHandler(
            processor.getLockingService(),
            processor.getBookkeeperService(),
            lifecycle,
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

        // Every batch reaches here exactly once, having finished its IO and CPU - so this is the
        // point at which work has genuinely been EXECUTED, as opposed to merely declared. Counted
        // here because the pre-existing completion gate could not see the difference: a skipped
        // batch left getResources().size() untouched, so refs-per-completion read a perfect k while
        // a quarter of batches never ran. Declared footprint and executed work are separate claims
        // and need separate counters.
        txn.recordBatchExecuted(txn.getCurrentBatchOperationCount());

        // --- operation cursor back edge -------------------------------------------------
        // Placed AFTER the phase validation and BEFORE the type split, so it fires for parents and
        // C alike. That is the pinned decision, not an accident of placement: a CU with k
        // resources under b=1 behaves like any other k-operation transaction, and the group timeline
        // becomes genuinely per-operation. Atomic C alongside interleaved parents would leave the
        // dynamic arm internally inconsistent in a way the ladder cannot see.
        //
        // hasMoreOperations() is false unless a batch acquisition engaged the cursor, which only
        // happens under S2PL with operationBatchSize > 0 - so no protocol conditional is needed
        // here and the default path is untouched.
        if (txn.hasMoreOperations()) {
            TransactionPhaseInitializer phaseInitializer = processor.getPhaseInitializer();
            // resetPhase BEFORE acquiring: on denial the coordinator blocks the transaction, and it
            // later wakes through ACIDAdmissionController.admitReadyTransaction, which gates real
            // reacquisition on phase == LOCK. Coming off a CPU boundary the phase is COMPLETE, so
            // without this the wake path would silently skip reacquisition. Resetting first makes a
            // mid-cursor denial indistinguishable from a first-attempt one and reuses every existing
            // block/wake/backoff path unchanged.
            txn.resetPhase(phaseInitializer.getIOModel());
            if (!processor.getLockCoordinator().acquireBatchLocks(txn)) {
                return false; // blocked - existing denial machinery already handled it
            }
            phaseInitializer.initialize(txn);
            return false; // not complete: more operations to execute
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
        return ConcurrencyModel.ACID;
    }
}
