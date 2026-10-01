package de.tum.tssimulator.bookkeeping;

import java.util.List;

import de.tum.tssimulator.monitoring.SimulationLogger;
import de.tum.tssimulator.queue.QueueManager;
import de.tum.tssimulator.queue.QueueType;

/**
 * BASE implementation of bookkeeper queue router.
 * 
 * BASE Routing Strategy:
 * - Route to RECONCILIATION queue (if dedicated processors) or CPU queue (if shared)
 * - Parent completes immediately (eventual consistency)
 * - Bookkeepers run asynchronously without blocking parent
 * - No lock phase: bookkeepers skip straight to IO/CPU or RECONCILIATION phase
 */
class BASEBookkeeperQueueRouter extends BookkeeperQueueRouter {
    private final boolean hasDedicatedReconcilers;
    
    public BASEBookkeeperQueueRouter(
        SimulationLogger logger,
        QueueManager queueManager,
        boolean hasDedicatedReconcilers
    ) {
        super(logger, queueManager);
        this.hasDedicatedReconcilers = hasDedicatedReconcilers;
    }
    
    @Override
    public void queueBookkeepers(List<BookkeeperTransaction> bookkeepers) {
        // Decide which queue based on processor availability
        QueueType targetQueue = hasDedicatedReconcilers 
            ? QueueType.RECONCILIATION 
            : QueueType.CPU;
        
        // Route bookkeepers (QueueManager handles phase preparation)
        for (BookkeeperTransaction bk : bookkeepers) {
            logger.metrics().incrementBookkeepers();
            queueManager.enqueue(bk, targetQueue);  // Phase prep happens inside enqueue()
        }
    }
}
