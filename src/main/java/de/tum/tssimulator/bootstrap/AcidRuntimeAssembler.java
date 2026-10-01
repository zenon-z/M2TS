package de.tum.tssimulator.bootstrap;

import java.util.function.Consumer;
import java.util.function.LongSupplier;

import de.tum.tssimulator.bookkeeping.BookkeeperCoordinatorFactory;
import de.tum.tssimulator.bookkeeping.BookkeeperCreationCoordinator;
import de.tum.tssimulator.bookkeeping.BookkeeperFactory;
import de.tum.tssimulator.bookkeeping.BookkeeperQueueRouter;
import de.tum.tssimulator.config.ConcurrencyModel;
import de.tum.tssimulator.config.SimulatorConfig;
import de.tum.tssimulator.core.Transaction;
import de.tum.tssimulator.execution.ACIDAdmissionController;
import de.tum.tssimulator.execution.ACIDStrategy;
import de.tum.tssimulator.execution.ACIDTransactionExecutor;
import de.tum.tssimulator.execution.AbstractAdmissionController;
import de.tum.tssimulator.execution.AdmissionWaitManager;
import de.tum.tssimulator.execution.BookkeeperOrchestrator;
import de.tum.tssimulator.execution.ConcurrencyStrategy;
import de.tum.tssimulator.execution.PhaseDispatcher;
import de.tum.tssimulator.execution.TransactionExecutionStrategy;
import de.tum.tssimulator.execution.TransactionProcessor;
import de.tum.tssimulator.execution.init.TransactionPhaseInitializer;
import de.tum.tssimulator.lifecycle.TransactionLifecyclePort;
import de.tum.tssimulator.locking.coordinator.TransactionLockCoordinator;
import de.tum.tssimulator.model.IOModel;
import de.tum.tssimulator.monitoring.SimulationLogger;
import de.tum.tssimulator.queue.QueueManager;
import de.tum.tssimulator.scheduling.Scheduler;
import de.tum.tssimulator.state.DirtyStateTracker;
import de.tum.tssimulator.state.TransactionGroupRegistry;

final class AcidRuntimeAssembler implements RuntimeModeAssembler {
    @Override
    public DirtyStateTracker createDirtySetManager() {
        return null;
    }

    @Override
    public boolean hasDedicatedReconcilers(SimulatorConfig config) {
        return false;
    }

    @Override
    public BookkeeperCreationCoordinator createBookkeeperCoordinator(
            BookkeeperFactory factory,
            SimulationLogger logger,
            TransactionGroupRegistry registry,
            BookkeeperOrchestrator orchestrator,
            DirtyStateTracker dirtySetManager,
            TransactionPhaseInitializer phaseInitializer,
            boolean hasDedicatedReconcilers,
            LongSupplier virtualTimeClock) {
        return BookkeeperCoordinatorFactory.createCreationCoordinator(
                ConcurrencyModel.ACID,
                factory,
                logger,
                registry,
                orchestrator,
                dirtySetManager,
                phaseInitializer,
                hasDedicatedReconcilers,
                virtualTimeClock);
    }

    @Override
    public BookkeeperQueueRouter createBookkeeperRouter(
            SimulationLogger logger,
            QueueManager queueManager,
            boolean hasDedicatedReconcilers) {
        return BookkeeperCoordinatorFactory.createQueueRouter(
                ConcurrencyModel.ACID,
                logger,
                queueManager,
                hasDedicatedReconcilers);
    }

    @Override
    public ConcurrencyStrategy createConcurrencyStrategy(
            TransactionLockCoordinator lockCoordinator,
            TransactionPhaseInitializer phaseInitializer,
            BookkeeperCreationCoordinator coordinator,
            BookkeeperQueueRouter router,
            SimulationLogger logger) {
        return new ACIDStrategy(lockCoordinator, phaseInitializer, coordinator, router);
    }

    @Override
    public TransactionExecutionStrategy createExecutionStrategy(
            TransactionProcessor processor,
            TransactionLifecyclePort lifecycle,
            TransactionGroupRegistry registry,
            BookkeeperOrchestrator orchestrator,
            DirtyStateTracker dirtySetManager) {
        return new ACIDTransactionExecutor(processor, lifecycle, registry, orchestrator);
    }

    @Override
    public PhaseDispatcher createReconciliationPhase(
            SimulatorConfig config,
            QueueManager queueManager,
            IOModel ioModel,
            DirtyStateTracker dirtySetManager,
            SimulationLogger logger,
            Consumer<Transaction> cpuComplete) {
        return null;
    }

    @Override
    public AbstractAdmissionController createAdmissionController(
            Scheduler scheduler,
            TransactionProcessor processor,
            IOModel ioModel,
            SimulationLogger logger,
            PhaseDispatcher downstreamPhases,
            int maxConcurrent,
            QueueManager queueManager,
            AdmissionWaitManager admissionWaitManager) {
        return new ACIDAdmissionController(
                scheduler,
                processor,
                ioModel,
                logger,
                downstreamPhases,
                maxConcurrent,
                queueManager,
                admissionWaitManager);
    }
}
