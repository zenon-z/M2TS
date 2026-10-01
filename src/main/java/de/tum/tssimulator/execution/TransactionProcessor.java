package de.tum.tssimulator.execution;

import de.tum.tssimulator.bookkeeping.BookkeeperCreationCoordinator;
import de.tum.tssimulator.bookkeeping.BookkeeperQueueRouter;
import de.tum.tssimulator.core.Transaction;
import de.tum.tssimulator.execution.ACIDStrategy;
import de.tum.tssimulator.execution.ConcurrencyStrategy;
import de.tum.tssimulator.execution.init.TransactionPhaseInitializer;
import de.tum.tssimulator.locking.coordinator.TransactionLockCoordinator;
import de.tum.tssimulator.model.IOModel;
import de.tum.tssimulator.monitoring.SimulationLogger;

/**
 * Transaction processor that delegates to services.
 * This class is being refactored to use LockingService and BookkeeperService.
 */
public class TransactionProcessor {
    private final LockingService lockingService;
    private final BookkeeperService bookkeeperService;
    private final TransactionLockCoordinator lockCoordinator;
    private final TransactionPhaseInitializer phaseInitializer;
    private final BookkeeperCreationCoordinator coordinator;
    private final BookkeeperQueueRouter router;
    private final IOModel ioModel;
    private final SimulationLogger logger;
    private final ConcurrencyStrategy strategy;  // Strategy for ACID/BASE

    public TransactionProcessor(TransactionLockCoordinator lockCoordinator,
                                TransactionPhaseInitializer phaseInitializer,
                                BookkeeperCreationCoordinator coordinator,
                                BookkeeperQueueRouter router,
                                IOModel ioModel,
                                SimulationLogger logger,
                                ConcurrencyStrategy strategy) {
        this.lockCoordinator = lockCoordinator;
        this.phaseInitializer = phaseInitializer;
        this.coordinator = coordinator;
        this.router = router;
        this.ioModel = ioModel;
        this.logger = logger;
        this.strategy = strategy;
        
        // Create services
        this.lockingService = new LockingService(lockCoordinator, strategy);
        this.bookkeeperService = new BookkeeperService(coordinator, router, ioModel);
    }
    
    // Backward compatibility constructor - defaults to ACID
    public TransactionProcessor(TransactionLockCoordinator lockCoordinator,
                                TransactionPhaseInitializer phaseInitializer,
                                BookkeeperCreationCoordinator coordinator,
                                BookkeeperQueueRouter router,
                                IOModel ioModel,
                                SimulationLogger logger) {
        this(lockCoordinator, phaseInitializer, coordinator, router, ioModel, logger, 
             createDefaultACIDStrategy(lockCoordinator, phaseInitializer, coordinator, router));
    }
    
    private static ConcurrencyStrategy createDefaultACIDStrategy(
            TransactionLockCoordinator lockCoordinator,
            TransactionPhaseInitializer phaseInitializer,
            BookkeeperCreationCoordinator coordinator,
            BookkeeperQueueRouter router) {
        return new ACIDStrategy(lockCoordinator, phaseInitializer, coordinator, router);
    }
    
    /** Exposed so the completion hook can re-enter the LOCK phase for the next operation batch
     * without duplicating this class's wiring. */
    public TransactionPhaseInitializer getPhaseInitializer() {
        return phaseInitializer;
    }

    public TransactionLockCoordinator getLockCoordinator() {
        return lockCoordinator;
    }
    
    public boolean isBaseMode() {
        return lockingService.isBaseMode();
    }

    public boolean process(Transaction txn) {
        // Delegate to locking service
        return lockingService.process(txn);
    }

    public void release(Transaction txn) {
        // Delegate to locking service
        lockingService.release(txn);
    }

    public void createAndQueueBookkeepers(Transaction txn) {
        // Delegate to bookkeeper service
        bookkeeperService.createAndQueueBookkeepers(txn);
    }
    
    public void periodicMaintenance(long virtualTime) {
        // Delegate to locking service
        lockingService.periodicMaintenance(virtualTime);
    }
    
    // Service accessors
    public LockingService getLockingService() {
        return lockingService;
    }
    
    public BookkeeperService getBookkeeperService() {
        return bookkeeperService;
    }
}
