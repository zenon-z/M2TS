package de.tum.tssimulator.execution;

import de.tum.tssimulator.bookkeeping.BookkeeperCreationCoordinator;
import de.tum.tssimulator.bookkeeping.BookkeeperQueueRouter;
import de.tum.tssimulator.core.Transaction;
import de.tum.tssimulator.core.TransactionEvent;
import de.tum.tssimulator.core.TransactionState;
import de.tum.tssimulator.execution.init.TransactionPhaseInitializer;
import de.tum.tssimulator.locking.coordinator.TransactionLockCoordinator;

/**
 * ACID concurrency strategy using Strict Strong 2-Phase Locking (SS2PL).
 * Wraps the existing TransactionProcessor for ACID semantics.
 */
public class ACIDStrategy implements ConcurrencyStrategy {
    private final TransactionLockCoordinator lockCoordinator;
    private final TransactionPhaseInitializer phaseInitializer;
    private final BookkeeperCreationCoordinator coordinator;
    private final BookkeeperQueueRouter router;
    
    public ACIDStrategy(TransactionLockCoordinator lockCoordinator,
                       TransactionPhaseInitializer phaseInitializer,
                       BookkeeperCreationCoordinator coordinator,
                       BookkeeperQueueRouter router) {
        this.lockCoordinator = lockCoordinator;
        this.phaseInitializer = phaseInitializer;
        this.coordinator = coordinator;
        this.router = router;
    }
    
    @Override
    public boolean process(Transaction txn) {
        // acquireBatchLocks, not acquireAllLocks: the FIRST acquisition is just the first batch.
        // The base implementation delegates straight to acquireAllLocks, so under C2PL - and under
        // S2PL at operationBatchSize=0 - this reduces to a single whole-transaction
        // acquisition with the cursor disengaged.
        if (!lockCoordinator.acquireBatchLocks(txn)) {
            return false;  // Blocked - couldn't acquire locks
        }
        
        // Transition PENDING → RUNNING after successful lock acquisition
        // (Retried transactions are already RUNNING, so only affects first-time transactions)
        if (txn.getState() == TransactionState.PENDING) {
            txn.transitionTo(TransactionEvent.LOCKS_ACQUIRED);
        }
        
        // Initialize execution phases (IO, CPU)
        phaseInitializer.initialize(txn);
        
        return true;
    }
    
    @Override
    public void release(Transaction txn) {
        // ACID: Release all locks after commit/abort
        lockCoordinator.releaseAllLocks(txn);
    }
    
    @Override
    public void periodicMaintenance(long virtualTime) {
        // ACID: No periodic maintenance needed
    }
    
    @Override
    public String getName() {
        return "ACID";
    }
}

