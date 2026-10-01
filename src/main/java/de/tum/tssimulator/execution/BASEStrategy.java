package de.tum.tssimulator.execution;

import de.tum.tssimulator.bookkeeping.BookkeeperCreationCoordinator;
import de.tum.tssimulator.bookkeeping.BookkeeperQueueRouter;
import de.tum.tssimulator.core.ExecutionPhase;
import de.tum.tssimulator.core.Transaction;
import de.tum.tssimulator.core.TransactionEvent;
import de.tum.tssimulator.core.TransactionState;
import de.tum.tssimulator.execution.init.TransactionPhaseInitializer;
import de.tum.tssimulator.monitoring.SimulationLogger;
import de.tum.tssimulator.queue.QueueType;

/**
 * BASE concurrency strategy using optimistic concurrency control.
 * 
 * Key characteristics:
 * - No locks acquired during transaction execution (always proceeds)
 * - Bookkeepers (CPRs) execute as background work on dedicated reconciliation processors
 * - Dirty set tracking prevents redundant bookkeeper spawning (fan-out collapse)
 * - Models real distributed systems (Cassandra, DynamoDB) with dedicated background threads
 * 
 * Design:
 * - Parent transactions complete immediately without blocking
 * - Spawned bookkeepers queue to RECONCILIATION queue
 * - Dedicated reconciliation processors execute bookkeepers continuously
 * - No periodic reconciliation - bookkeepers ARE the reconciliation work
 */
public class BASEStrategy implements ConcurrencyStrategy {
    private final TransactionPhaseInitializer phaseInitializer;
    private final BookkeeperCreationCoordinator coordinator;
    private final BookkeeperQueueRouter router;
    private final SimulationLogger logger;
    
    public BASEStrategy(TransactionPhaseInitializer phaseInitializer,
                       BookkeeperCreationCoordinator coordinator,
                       BookkeeperQueueRouter router,
                       SimulationLogger logger) {
        this.phaseInitializer = phaseInitializer;
        this.coordinator = coordinator;
        this.router = router;
        this.logger = logger;
    }
    
    @Override
    public boolean process(Transaction txn) {
        // BASE: No locks needed - always proceed optimistically
        
        // Transition PENDING → RUNNING using READY_TO_EXECUTE (BASE-specific event)
        if (txn.getState() == TransactionState.PENDING) {
            txn.transitionTo(TransactionEvent.READY_TO_EXECUTE);
        }
        
        // Skip LOCK phase entirely - go straight to IO phase
        txn.preparePhase(ExecutionPhase.IO, phaseInitializer.getIOModel());
        phaseInitializer.getQueueManager().enqueue(txn, QueueType.IO);
        
        // Bookkeepers will be created after CPU phase completes
        // (in TransactionGroupCompletionHandler.completeTransaction)
        
        // Always return true - no blocking in BASE
        return true;
    }
    
    @Override
    public void release(Transaction txn) {
        // BASE: No locks to release, no state to clean up
    }
    
    @Override
    public void periodicMaintenance(long virtualTime) {
        // BASE: No periodic work needed - bookkeepers execute continuously on reconciliation processors
    }
    
    @Override
    public String getName() {
        return "BASE";
    }
}
