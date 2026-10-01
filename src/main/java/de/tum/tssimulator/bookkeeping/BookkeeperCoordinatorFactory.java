package de.tum.tssimulator.bookkeeping;

import java.util.function.LongSupplier;

import de.tum.tssimulator.config.ConcurrencyModel;
import de.tum.tssimulator.execution.BookkeeperOrchestrator;
import de.tum.tssimulator.execution.init.TransactionPhaseInitializer;
import de.tum.tssimulator.monitoring.SimulationLogger;
import de.tum.tssimulator.queue.QueueManager;
import de.tum.tssimulator.state.DirtyStateTracker;
import de.tum.tssimulator.state.TransactionGroupRegistry;

/**
 * Factory for creating bookkeeper coordinators and routers based on concurrency model.
 * Encapsulates the complexity of instantiating ACID vs BASE implementations.
 */
public class BookkeeperCoordinatorFactory {
    
    /**
     * Create appropriate BookkeeperCreationCoordinator for the concurrency model.
     * 
     * @param model Concurrency model (ACID or BASE)
     * @param factory Factory for creating bookkeeper transactions
     * @param logger Simulation logger
     * @param registry Transaction group registry
     * @param orchestrator Orchestrator for ON_RESTRICT/ON_CASCADE handling
     * @param dirtySetManager Dirty set manager (BASE only, null for ACID)
     * @param phaseInitializer Phase initializer (BASE only, null for ACID)
     * @param hasDedicatedReconcilers Whether BASE mode has dedicated reconciliation processors
     * @param virtualTimeClock Supplier for current virtual time (BASE only, null for ACID)
     * @return Appropriate coordinator implementation
     */
    public static BookkeeperCreationCoordinator createCreationCoordinator(
        ConcurrencyModel model,
        BookkeeperFactory factory,
        SimulationLogger logger,
        TransactionGroupRegistry registry,
        BookkeeperOrchestrator orchestrator,
        DirtyStateTracker dirtySetManager,
        TransactionPhaseInitializer phaseInitializer,
        boolean hasDedicatedReconcilers,
        LongSupplier virtualTimeClock
    ) {
        if (model == ConcurrencyModel.ACID) {
            return new ACIDBookkeeperCreator(
                factory,
                logger,
                registry,
                orchestrator
            );
        } else {
            return new BASEBookkeeperCreator(
                factory,
                logger,
                registry,
                orchestrator,
                dirtySetManager,
                phaseInitializer,
                hasDedicatedReconcilers,
                virtualTimeClock
            );
        }
    }
    
    /**
     * Create appropriate BookkeeperQueueRouter for the concurrency model.
     * 
     * @param model Concurrency model (ACID or BASE)
     * @param logger Simulation logger
     * @param queueManager Queue manager for enqueueing transactions
     * @param hasDedicatedReconcilers Whether BASE mode has dedicated reconciliation processors
     * @return Appropriate router implementation
     */
    public static BookkeeperQueueRouter createQueueRouter(
        ConcurrencyModel model,
        SimulationLogger logger,
        QueueManager queueManager,
        boolean hasDedicatedReconcilers
    ) {
        if (model == ConcurrencyModel.ACID) {
            return new ACIDBookkeeperQueueRouter(logger, queueManager);
        } else {
            return new BASEBookkeeperQueueRouter(logger, queueManager, hasDedicatedReconcilers);
        }
    }
}
