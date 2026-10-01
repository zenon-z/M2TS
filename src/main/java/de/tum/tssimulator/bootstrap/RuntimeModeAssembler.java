package de.tum.tssimulator.bootstrap;

import java.util.function.Consumer;
import java.util.function.LongSupplier;

import de.tum.tssimulator.bookkeeping.BookkeeperCreationCoordinator;
import de.tum.tssimulator.bookkeeping.BookkeeperFactory;
import de.tum.tssimulator.bookkeeping.BookkeeperQueueRouter;
import de.tum.tssimulator.config.SimulatorConfig;
import de.tum.tssimulator.core.Transaction;
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

interface RuntimeModeAssembler {
    DirtyStateTracker createDirtySetManager();

    boolean hasDedicatedReconcilers(SimulatorConfig config);

    BookkeeperCreationCoordinator createBookkeeperCoordinator(
            BookkeeperFactory factory,
            SimulationLogger logger,
            TransactionGroupRegistry registry,
            BookkeeperOrchestrator orchestrator,
            DirtyStateTracker dirtySetManager,
            TransactionPhaseInitializer phaseInitializer,
            boolean hasDedicatedReconcilers,
            LongSupplier virtualTimeClock);

    BookkeeperQueueRouter createBookkeeperRouter(
            SimulationLogger logger,
            QueueManager queueManager,
            boolean hasDedicatedReconcilers);

    ConcurrencyStrategy createConcurrencyStrategy(
            TransactionLockCoordinator lockCoordinator,
            TransactionPhaseInitializer phaseInitializer,
            BookkeeperCreationCoordinator coordinator,
            BookkeeperQueueRouter router,
            SimulationLogger logger);

    TransactionExecutionStrategy createExecutionStrategy(
            TransactionProcessor processor,
            TransactionLifecyclePort lifecycle,
            TransactionGroupRegistry registry,
            BookkeeperOrchestrator orchestrator,
            DirtyStateTracker dirtySetManager);

    PhaseDispatcher createReconciliationPhase(
            SimulatorConfig config,
            QueueManager queueManager,
            IOModel ioModel,
            DirtyStateTracker dirtySetManager,
            SimulationLogger logger,
            Consumer<Transaction> cpuComplete);

    AbstractAdmissionController createAdmissionController(
            Scheduler scheduler,
            TransactionProcessor processor,
            IOModel ioModel,
            SimulationLogger logger,
            PhaseDispatcher downstreamPhases,
            int maxConcurrent,
            QueueManager queueManager,
            AdmissionWaitManager admissionWaitManager);
}
