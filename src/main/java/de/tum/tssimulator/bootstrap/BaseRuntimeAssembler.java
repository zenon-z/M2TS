package de.tum.tssimulator.bootstrap;

import java.util.ArrayList;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

import de.tum.tssimulator.bookkeeping.BookkeeperCoordinatorFactory;
import de.tum.tssimulator.bookkeeping.BookkeeperCreationCoordinator;
import de.tum.tssimulator.bookkeeping.BookkeeperFactory;
import de.tum.tssimulator.bookkeeping.BookkeeperQueueRouter;
import de.tum.tssimulator.config.ConcurrencyModel;
import de.tum.tssimulator.config.SimulatorConfig;
import de.tum.tssimulator.core.Transaction;
import de.tum.tssimulator.execution.AbstractAdmissionController;
import de.tum.tssimulator.execution.AdmissionWaitManager;
import de.tum.tssimulator.execution.BASEAdmissionController;
import de.tum.tssimulator.execution.BASEStrategy;
import de.tum.tssimulator.execution.BASETransactionExecutor;
import de.tum.tssimulator.execution.BookkeeperOrchestrator;
import de.tum.tssimulator.execution.ConcurrencyStrategy;
import de.tum.tssimulator.execution.PhaseDispatcher;
import de.tum.tssimulator.execution.ReconciliationPhaseDispatcher;
import de.tum.tssimulator.execution.ReconciliationProcessorUnit;
import de.tum.tssimulator.execution.TransactionExecutionStrategy;
import de.tum.tssimulator.execution.TransactionProcessor;
import de.tum.tssimulator.execution.init.TransactionPhaseInitializer;
import de.tum.tssimulator.lifecycle.TransactionLifecyclePort;
import de.tum.tssimulator.locking.coordinator.TransactionLockCoordinator;
import de.tum.tssimulator.model.IOModel;
import de.tum.tssimulator.monitoring.SimulationLogger;
import de.tum.tssimulator.queue.QueueManager;
import de.tum.tssimulator.scheduling.Scheduler;
import de.tum.tssimulator.state.DirtySetManager;
import de.tum.tssimulator.state.DirtyStateTracker;
import de.tum.tssimulator.state.TransactionGroupRegistry;

final class BaseRuntimeAssembler implements RuntimeModeAssembler {
    @Override
    public DirtyStateTracker createDirtySetManager() {
        return new DirtySetManager();
    }

    @Override
    public boolean hasDedicatedReconcilers(SimulatorConfig config) {
        return config.numReconcilerProcessors > 0;
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
                ConcurrencyModel.BASE,
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
                ConcurrencyModel.BASE,
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
        return new BASEStrategy(phaseInitializer, coordinator, router, logger);
    }

    @Override
    public TransactionExecutionStrategy createExecutionStrategy(
            TransactionProcessor processor,
            TransactionLifecyclePort lifecycle,
            TransactionGroupRegistry registry,
            BookkeeperOrchestrator orchestrator,
            DirtyStateTracker dirtySetManager) {
        return new BASETransactionExecutor(processor, lifecycle, registry, orchestrator, dirtySetManager);
    }

    @Override
    public PhaseDispatcher createReconciliationPhase(
            SimulatorConfig config,
            QueueManager queueManager,
            IOModel ioModel,
            DirtyStateTracker dirtySetManager,
            SimulationLogger logger,
            Consumer<Transaction> cpuComplete) {
        if (!hasDedicatedReconcilers(config)) {
            return null;
        }

        var reconciliationProcessors = new ArrayList<ReconciliationProcessorUnit>();
        for (int i = 0; i < config.numReconcilerProcessors; i++) {
            reconciliationProcessors.add(new ReconciliationProcessorUnit(cpuComplete));
        }

        return new ReconciliationPhaseDispatcher(
                reconciliationProcessors,
                queueManager,
                ioModel,
                dirtySetManager,
                logger);
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
        return new BASEAdmissionController(
                scheduler,
                processor,
                ioModel,
                logger,
                downstreamPhases,
                maxConcurrent,
                queueManager);
    }
}
