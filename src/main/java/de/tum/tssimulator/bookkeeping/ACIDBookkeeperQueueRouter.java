package de.tum.tssimulator.bookkeeping;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import de.tum.tssimulator.config.CuQueuePolicy;
import de.tum.tssimulator.monitoring.LogEvent;
import de.tum.tssimulator.monitoring.SimulationLogger;
import de.tum.tssimulator.queue.QueueManager;
import de.tum.tssimulator.queue.QueueType;

/**
 * ACID implementation of bookkeeper queue router.
 * 
 * ACID Routing Strategy:
 * - Prepend to PENDING queue (priority execution)
 * - Parent holds locks waiting for bookkeepers to complete
 * - Bookkeepers go through full LOCK → IO → CPU flow
 * - High priority: must complete ASAP to release parent locks
 */
class ACIDBookkeeperQueueRouter extends BookkeeperQueueRouter {
    
    public ACIDBookkeeperQueueRouter(
        SimulationLogger logger,
        QueueManager queueManager
    ) {
        super(logger, queueManager);
    }
    
    @Override
    public void queueBookkeepers(List<BookkeeperTransaction> bookkeepers) {
        // ACID: Prepend to PENDING queue for priority execution
        // Parent is holding locks in waiting room, so bookkeepers need to complete ASAP
        
        // PREPEND_IN_ORDER walks the batch BACKWARDS so that prepending each one leaves the
        // batch in its original order at the front. That is the only difference from PREPEND,
        // which walks forwards and therefore reverses the batch - see CuQueuePolicy.
        List<BookkeeperTransaction> order = bookkeepers;
        if (cuQueuePolicy == CuQueuePolicy.PREPEND_IN_ORDER) {
            order = new ArrayList<>(bookkeepers);
            Collections.reverse(order);
        }
        for (BookkeeperTransaction bk : order) {
            logger.logEvent(new LogEvent(
                LogEvent.Type.BOOKKEEPER_QUEUED,
                bk.getId(),
                LogEvent.NO_VIRTUAL_TIME,
                "bookkeeper-queued"
            ));
            logger.metrics().incrementBookkeepers();
            
            // Prepend keeps CUs ahead of fresh parents, which is the REASONED half of this
            // policy: the parent holds locks in the waiting room while its CUs run. APPEND drops
            // that priority and is the arm that tests whether it is load-bearing.
            if (cuQueuePolicy == CuQueuePolicy.APPEND) {
                queueManager.enqueue(bk, QueueType.PENDING);
            } else {
                queueManager.prepend(bk, QueueType.PENDING);
            }
        }
    }
}
