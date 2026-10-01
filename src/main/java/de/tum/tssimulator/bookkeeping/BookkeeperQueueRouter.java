package de.tum.tssimulator.bookkeeping;

import java.util.List;

import de.tum.tssimulator.config.CuQueuePolicy;
import de.tum.tssimulator.monitoring.SimulationLogger;
import de.tum.tssimulator.queue.QueueManager;

/**
 * Abstract router for queueing bookkeepers to execution queues.
 * 
 * Responsibilities:
 * - Route bookkeepers to appropriate execution queue
 * - Apply mode-specific priority logic
 * - Log queueing events
 * 
 * Separation of concerns:
 * - BookkeeperCreationCoordinator decides WHAT bookkeepers to create
 * - BookkeeperQueueRouter decides WHERE bookkeepers execute
 */
public abstract class BookkeeperQueueRouter {

    /** CU placement policy; PREPEND is the historical behaviour. See CuQueuePolicy. */
    protected CuQueuePolicy cuQueuePolicy = CuQueuePolicy.PREPEND;

    public void setCuQueuePolicy(CuQueuePolicy policy) {
        this.cuQueuePolicy = policy == null ? CuQueuePolicy.PREPEND : policy;
    }

    public CuQueuePolicy getCuQueuePolicy() {
        return cuQueuePolicy;
    }
    protected final SimulationLogger logger;
    protected final QueueManager queueManager;
    
    public BookkeeperQueueRouter(
        SimulationLogger logger,
        QueueManager queueManager
    ) {
        this.logger = logger;
        this.queueManager = queueManager;
    }
    
    /**
     * Queue bookkeepers to appropriate execution queue(s).
     * Mode-specific logic for queue selection and priority.
     * 
     * @param bookkeepers List of bookkeepers to queue
     */
    public abstract void queueBookkeepers(List<BookkeeperTransaction> bookkeepers);
}
