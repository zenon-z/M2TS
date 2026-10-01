package de.tum.tssimulator.locking.policy;

import de.tum.tssimulator.bookkeeping.BookkeeperTransaction;
import de.tum.tssimulator.core.Transaction;
import de.tum.tssimulator.core.TransactionEvent;
import de.tum.tssimulator.core.TransactionState;
import de.tum.tssimulator.locking.core.LockManager;
import de.tum.tssimulator.monitoring.LogEvent;
import de.tum.tssimulator.monitoring.SimulationLogger;
import de.tum.tssimulator.queue.CentralQueueManager;
import de.tum.tssimulator.queue.QueueManager;
import de.tum.tssimulator.queue.QueueType;

public class SoftDenyDecision implements LockDecision {
    private final QueueType blockQueue;

    public SoftDenyDecision(QueueType blockQueue) {
        this.blockQueue = blockQueue;
    }

    @Override
    public boolean granted() {
        return false;
    }

    @Override
    public void handle(Transaction requester, String resource, LockManager lockManager,
                       QueueManager queueManager, SimulationLogger logger) {
        logger.logEvent(new LogEvent(LogEvent.Type.LOCK_DENIED, requester.getId(), 0L,
                resource));
        
        // Skip if transaction is already in a terminal state
        TransactionState state = requester.getState();
        if (state == TransactionState.COMPLETED ||
            state == TransactionState.ABORTED) {
            // Transaction already finished - don't try to block it
            return;
        }
        
        // Transition to BLOCKED state (lock acquisition only happens from PENDING state)
        requester.transitionTo(TransactionEvent.LOCK_DENIED);

        // F5 (VLDB reviewer response): lock_wait_p50/p95/p99 - mark the wait-interval start.
        // Idempotent in effect: if this member is already queued for a different lock (rare,
        // since requesters normally request all locks together), the earlier start time wins,
        // so the recorded wait spans the whole blocked interval rather than resetting per lock.
        if (requester.getLockWaitStart() < 0) {
            long currentTime = (queueManager instanceof CentralQueueManager cqm) ? cqm.getCurrentTime() : 0L;
            requester.setLockWaitStart(currentTime);
        }

        // Register this transaction as waiting for this specific lock
        lockManager.registerWaiter(requester, resource);

        // BLOCKED phase, the requester's side of the same marking TransactionLockCoordinator
        // .blockGroupMember() does for its siblings. Parent-only, like every markPhase site.
        if (!(requester instanceof BookkeeperTransaction)) {
            long blockStart = (queueManager instanceof CentralQueueManager cqm)
                    ? cqm.getCurrentTime() : 0L;
            requester.markBlocked(blockStart);
        }
        queueManager.enqueue(requester, blockQueue);
    }
}
