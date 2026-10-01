package de.tum.tssimulator.locking.policy;

import de.tum.tssimulator.core.Transaction;
import de.tum.tssimulator.core.TransactionGroupState;
import de.tum.tssimulator.locking.core.LockManager;
import de.tum.tssimulator.monitoring.LogEvent;
import de.tum.tssimulator.monitoring.SimulationLogger;
import de.tum.tssimulator.queue.QueueManager;
import de.tum.tssimulator.scheduling.TransactionScheduler;

public class HardDenyDecision implements LockDecision {
    private final TransactionScheduler scheduler;
    
    public HardDenyDecision(TransactionScheduler scheduler) {
        this.scheduler = scheduler;
    }
    
    @Override public boolean granted() { return false; }

    /**
     * Aborts the REQUESTER's group (standard cautious waiting).
     *
     * <p>{@code logger} may be null, and is dereferenced four times below:
     * {@code SS2PLLockManager.tryAcquireAllLocks} has no logger to hand, so an unguarded
     * dereference throws NPE on the first hard deny reached via the preclaim path instead of
     * aborting. That path is rare rather than unreachable - it needs a preclaim to find a lock
     * whose holder is itself blocked. The abort must happen either way; only the logging is
     * optional.
     */
    @Override
    public void handle(Transaction txn, String resource, LockManager lm, QueueManager qm, SimulationLogger logger) {
        if (logger != null) {
            logger.logEvent(new LogEvent(LogEvent.Type.LOCK_DENIED, txn.getId(), 0,
                    "resource=" + resource + " decision=hard-deny"));
        }
        long currentTime = qm.getCurrentTime();
        
        // Defensive null check for group and state validation
        if (txn.getGroup() == null) {
            // Transaction has no group - cannot abort
            return;
        }
        
        // Check if group is in a valid state to abort (not already completed/aborted)
        TransactionGroupState state = txn.getGroup().getState();
        if (state == TransactionGroupState.COMPLETED || state == TransactionGroupState.ABORTED) {
            // Group already in terminal state - skip abort
            return;
        }
        
        txn.getGroup().abortAll(qm, lm, scheduler, currentTime, logger, null);
        if (logger != null) {
            logger.logEvent(new LogEvent(LogEvent.Type.TRANSACTION_ABORTED, txn.getId(), 0, "name=aborted delta=1"));
            logger.metrics().incrementAborted();
            logger.metrics().recordAbortReason("cautious_wait");
        }
    }
}
