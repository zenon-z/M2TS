package de.tum.tssimulator.execution;

import de.tum.tssimulator.bookkeeping.BookkeeperCreationCoordinator;
import de.tum.tssimulator.bookkeeping.BookkeeperQueueRouter;
import de.tum.tssimulator.bookkeeping.BookkeeperTransaction;
import de.tum.tssimulator.core.Transaction;
import de.tum.tssimulator.model.IOModel;

import java.util.List;

/**
 * Manages bookkeeper creation and queuing.
 * 
 * Responsibilities:
 * - Create bookkeepers for parent transactions
 * - Prepare phases for BASE mode bookkeepers
 * - Queue bookkeepers to appropriate execution queues
 * 
 * This service encapsulates bookkeeper orchestration and provides
 * a cleaner abstraction than passing TransactionProcessor everywhere.
 */
public class BookkeeperService {
    private final BookkeeperCreationCoordinator creator;
    private final BookkeeperQueueRouter router;
    private final IOModel ioModel;

    public BookkeeperService(BookkeeperCreationCoordinator creator,
                            BookkeeperQueueRouter router,
                            IOModel ioModel) {
        this.creator = creator;
        this.router = router;
        this.ioModel = ioModel;
    }

    /**
     * Create and queue bookkeepers for a transaction.
     * 
     * This method:
     * 1. Creates bookkeepers using the coordinator
     * 2. Prepares phases for BASE mode if needed
     * 3. Queues bookkeepers using the router
     * 
     * @param parent Parent transaction that triggers bookkeeper creation
     */
    public void createAndQueueBookkeepers(Transaction parent) {
        // Create bookkeepers using coordinator
        List<BookkeeperTransaction> bookkeepers = creator.createBookkeepers(parent);
        
        // Prepare phases when the configured coordinator needs it.
        if (!bookkeepers.isEmpty()) {
            creator.preparePhases(bookkeepers);
        }

        // Queue bookkeepers using router
        if (!bookkeepers.isEmpty()) {
            router.queueBookkeepers(bookkeepers);
        }
    }
    
    /**
     * Get the underlying bookkeeper creation coordinator.
     * 
     * @return BookkeeperCreationCoordinator instance
     */
    public BookkeeperCreationCoordinator getCreator() {
        return creator;
    }
    
    /**
     * Get the underlying bookkeeper queue router.
     * 
     * @return BookkeeperQueueRouter instance
     */
    public BookkeeperQueueRouter getRouter() {
        return router;
    }
}
