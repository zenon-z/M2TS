package de.tum.tssimulator.bookkeeping;

import java.util.List;

import de.tum.tssimulator.core.Transaction;
import de.tum.tssimulator.execution.BookkeeperOrchestrator;
import de.tum.tssimulator.monitoring.SimulationLogger;
import de.tum.tssimulator.state.TransactionGroupRegistry;

/**
 * ACID implementation of bookkeeper creation coordinator.
 * 
 * ACID Characteristics:
 * - No filtering: All bookkeepers are created (strong consistency)
 * - No dirty set: Resources don't track pending bookkeepers
 * - Immediate execution: Bookkeepers prepended to PENDING queue for priority
 */
class ACIDBookkeeperCreator extends BookkeeperCreationCoordinator {
    
    public ACIDBookkeeperCreator(
        BookkeeperFactory factory,
        SimulationLogger logger,
        TransactionGroupRegistry registry,
        BookkeeperOrchestrator orchestrator
    ) {
        super(factory, logger, registry, orchestrator);
    }
    
    @Override
    protected List<BookkeeperTransaction> filterBookkeepers(
        List<BookkeeperTransaction> bookkeepers, 
        Transaction parent
    ) {
        // ACID: No filtering - create all bookkeepers for strong consistency
        return bookkeepers;
    }
    
    @Override
    public void onBookkeeperCompleted(BookkeeperTransaction bookkeeper) {
        // ACID: No cleanup needed (no dirty set to manage)
    }
}
