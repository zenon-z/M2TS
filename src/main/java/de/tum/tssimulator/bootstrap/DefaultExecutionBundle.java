package de.tum.tssimulator.bootstrap;

import java.util.ArrayList;
import java.util.function.Consumer;

import de.tum.tssimulator.bookkeeping.BookkeeperCreationCoordinator;
import de.tum.tssimulator.bookkeeping.BookkeeperQueueRouter;
import de.tum.tssimulator.config.SimulatorConfig;
import de.tum.tssimulator.core.Transaction;
import de.tum.tssimulator.execution.ACIDAdmissionController;
import de.tum.tssimulator.execution.AbstractAdmissionController;
import de.tum.tssimulator.execution.AdmissionWaitManager;
import de.tum.tssimulator.execution.BookkeeperOrchestrator;
import de.tum.tssimulator.execution.CompositePhaseDispatcher;
import de.tum.tssimulator.execution.ConcurrencyStrategy;
import de.tum.tssimulator.execution.PhaseDispatcher;
import de.tum.tssimulator.execution.PhaseDispatcherGroup;
import de.tum.tssimulator.execution.SimulatedProcessorUnit;
import de.tum.tssimulator.execution.TransactionExecutionStrategy;
import de.tum.tssimulator.execution.TransactionExecutor;
import de.tum.tssimulator.execution.TransactionProcessor;
import de.tum.tssimulator.execution.init.TransactionPhaseInitializer;
import de.tum.tssimulator.lifecycle.TransactionGroupAbortCoordinator;
import de.tum.tssimulator.lifecycle.TransactionLifecycleManager;
import de.tum.tssimulator.locking.coordinator.TransactionLockCoordinator;
import de.tum.tssimulator.model.IOModel;
import de.tum.tssimulator.monitoring.SimulationLogger;
import de.tum.tssimulator.queue.CentralQueueManager;
import de.tum.tssimulator.queue.QueueManager;
import de.tum.tssimulator.queue.QueueType;
import de.tum.tssimulator.scheduling.Scheduler;
import de.tum.tssimulator.scheduling.TransactionScheduler;
import de.tum.tssimulator.state.DirtyStateTracker;
import de.tum.tssimulator.state.TransactionGroupRegistry;

public class DefaultExecutionBundle implements ExecutionBundle {
    private static final String DISABLE_MPL_LIMIT_PROPERTY = "ts.simulator.disableMplLimit";

    private IOModel ioModel;
    private TransactionPhaseInitializer initializer;
    private TransactionProcessor processor;
    private PhaseDispatcher dispatcher;
    private TransactionExecutor executor;
    private TransactionLifecycleManager lifecycle;
    private AbstractAdmissionController intake;  // For wiring StepContext
    private DirtyStateTracker dirtySetManager;  // BASE mode: tracks dirty resources

    // Backward-compatible constructor (used by tests); delegates to unified init
    public DefaultExecutionBundle(SimulatorConfig cfg,
            QueueManager queueManager,
            Scheduler scheduler,
            TransactionLockCoordinator txnCoord,
            BookkeeperCreationCoordinator coordinator,
            BookkeeperQueueRouter router,
            SimulationLogger logger) {
        // Build minimal registry for tests
        TransactionGroupRegistry registry = new TransactionGroupRegistry();
        DirtyStateTracker dirtySetManager =
                RuntimeModeAssemblers.forConfig(cfg).createDirtySetManager();
        init(cfg, queueManager, scheduler, txnCoord, coordinator, router, registry, logger, dirtySetManager, null, null);
    }

    // New constructor with explicit registry
    public DefaultExecutionBundle(SimulatorConfig cfg,
                                  QueueManager queueManager,
                                  Scheduler scheduler,
                                  TransactionLockCoordinator txnCoord,
                                  BookkeeperCreationCoordinator coordinator,
                                  BookkeeperQueueRouter router,
                                  TransactionGroupRegistry registry,
                                  SimulationLogger logger) {
        DirtyStateTracker dirtySetManager =
                RuntimeModeAssemblers.forConfig(cfg).createDirtySetManager();
        init(cfg, queueManager, scheduler, txnCoord, coordinator, router, registry, logger, dirtySetManager, null, null);
    }

    // Constructor with explicit dirtySetManager (used by SimulatorContext)
    public DefaultExecutionBundle(SimulatorConfig cfg,
                                  QueueManager queueManager,
                                  Scheduler scheduler,
                                  TransactionLockCoordinator txnCoord,
                                  BookkeeperCreationCoordinator coordinator,
                                  BookkeeperQueueRouter router,
                                  TransactionGroupRegistry registry,
                                  SimulationLogger logger,
                                  DirtyStateTracker dirtySetManager,
                                  AdmissionWaitManager admissionWaitManager) {
        init(cfg, queueManager, scheduler, txnCoord, coordinator, router, registry, logger, dirtySetManager, null, admissionWaitManager);
    }

    // Constructor with explicit IOModel (used by SimulatorContext for Fix A)
    public DefaultExecutionBundle(SimulatorConfig cfg,
                                  QueueManager queueManager,
                                  Scheduler scheduler,
                                  TransactionLockCoordinator txnCoord,
                                  BookkeeperCreationCoordinator coordinator,
                                  BookkeeperQueueRouter router,
                                  TransactionGroupRegistry registry,
                                  SimulationLogger logger,
                                  IOModel ioModel,
                                  DirtyStateTracker dirtySetManager,
                                  AdmissionWaitManager admissionWaitManager) {
        init(cfg, queueManager, scheduler, txnCoord, coordinator, router, registry, logger, dirtySetManager, ioModel, admissionWaitManager);
    }

    private void init(SimulatorConfig cfg,
                      QueueManager queueManager,
                      Scheduler scheduler,
                      TransactionLockCoordinator txnCoord,
                      BookkeeperCreationCoordinator coordinator,
                      BookkeeperQueueRouter router,
                      TransactionGroupRegistry registry,
                      SimulationLogger logger,
                      DirtyStateTracker dirtySetManager,
                      IOModel ioModel,
                      AdmissionWaitManager admissionWaitManager) {
        RuntimeModeAssembler modeAssembler = RuntimeModeAssemblers.forConfig(cfg);
        
        // Store dirty set manager for BASE mode
        this.dirtySetManager = dirtySetManager;

        this.ioModel = (ioModel != null) ? ioModel : new IOModel(cfg);
        this.initializer = new TransactionPhaseInitializer(queueManager, this.ioModel);
        
        ConcurrencyStrategy strategy = modeAssembler.createConcurrencyStrategy(
            txnCoord,
            initializer,
            coordinator,
            router,
            logger
        );
        
        // Calculate max concurrent first since lifecycle needs it
        int maxConcurrent = Math.min(cfg.numIoProcessors, cfg.numCpuProcessors);
        
        this.processor = new TransactionProcessor(txnCoord, initializer, coordinator, router, this.ioModel, logger, strategy);
        this.lifecycle = new TransactionLifecycleManager(queueManager, cfg, logger, maxConcurrent);
        
        // Orchestrator for ON_RESTRICT policy checking (simplified - only needs config)
        BookkeeperOrchestrator orchestrator = new BookkeeperOrchestrator(cfg);

        // Create proper execution strategy based on concurrency model
        TransactionExecutionStrategy executionStrategy =
                modeAssembler.createExecutionStrategy(processor, lifecycle, registry, orchestrator, dirtySetManager);
        
        this.executor = new TransactionExecutor(executionStrategy);

        // LOCK POOL WIDTH: cfg.lockPoolSize when set, otherwise min(numIo, numCpu).
        //
        // THE DEFAULT IS THE LAST REMNANT OF MPL = cores, AND IT IS NOT AN ADMISSION BOUND.
        // Scaling the pool with the core count does NOT make it the matched pair of the admission
        // cap: MPL is an independent parameter (see admissionCapacity below, which is
        // cfg.maxConcurrentRunning), so at 24 cores the lock stage is 24 servers against a cap of
        // 192. The reason to scale it at all is the other one: a single hardcoded unit would make
        // the LOCK phase an artificial single-server bottleneck ahead of the IO/CPU stages it
        // feeds.
        //
        // MEASURED INERT, so the width is a naming question rather than a live confound. Pinning
        // it to 24 and comparing against the default, 1 seed, f = 0.80,
        // theta = 0.5, k = 10:
        //
        //   C2PL  cores 1/2/4: 1.24/3.20/9.06 tps default vs 1.24/3.24/8.86 pinned (ratio
        //         1.000/1.012/0.978). Per-core scaling 1.000/1.290/1.827 vs 1.000/1.306/1.786 -
        //         so the 1 -> 4 core SUPERLINEARITY SURVIVES the pin and is NOT the lock-server
        //         count. That question stays open (see WorkConservationAudit).
        //   S2PL  cores 1/2/4 at operationBatchSize 1: ratio 1.000/0.990/1.000, and that cell is
        //         sublinear anyway (per-core 1.000/0.759/0.896).
        //   Controls unaffected at 1 core: 100.00 tps C2PL, 99.34 S2PL, both widths.
        //
        // Keep the knob: the measurement above is the only thing that makes the default defensible,
        // and it is cheap to re-run if the pool geometry changes.
        //
        // Deliberately does NOT touch the maxConcurrent handed to the admission controller below -
        // that value is overwritten by setProcessorCapacity(admissionCapacity) anyway, and coupling
        // the two here would put the admission cap back on the core count.
        int lockPoolWidth = cfg.lockPoolSize != null ? Math.max(1, cfg.lockPoolSize) : maxConcurrent;
        // Unit affinity for LOCK too - see TransactionGroup.pinnedLockUnitIndex's comment for
        // why this pool was initially (wrongly) excluded.
        if (queueManager instanceof CentralQueueManager centralQueueManagerForLock) {
            centralQueueManagerForLock.initPooledQueues(QueueType.LOCK, lockPoolWidth);
        }
        var lockProcessors = new ArrayList<SimulatedProcessorUnit>();
        for (int i = 0; i < lockPoolWidth; i++) {
            lockProcessors.add(new SimulatedProcessorUnit(
                    QueueType.LOCK, t -> queueManager.enqueue(t, QueueType.IO), logger, i));
        }
        
        var lockPhase = new CompositePhaseDispatcher(
                lockProcessors,
                queueManager,
                this.ioModel,
                QueueType.LOCK
        );
        
        // Register with queue manager so blocked transactions can be cleared from processors
        if (queueManager instanceof CentralQueueManager) {
            ((CentralQueueManager) queueManager).registerPhaseDispatcher(lockPhase);
        }

        // Create IO processors
        // Unit-affinity storage (CentralQueueManager.pooledOwnQueues) must exist before any unit
        // in this pool can be assigned - see TransactionGroup.pinUnitIfAbsent()'s javadoc - and
        // needs the exact pool size so unit index i here lines up with pooledOwnQueues index i.
        if (queueManager instanceof CentralQueueManager centralQueueManagerForIo) {
            centralQueueManagerForIo.initPooledQueues(QueueType.IO, cfg.numIoProcessors);
        }
        var ioProcessors = new ArrayList<SimulatedProcessorUnit>();
        for (int i = 0; i < cfg.numIoProcessors; i++) {
            ioProcessors.add(new SimulatedProcessorUnit(
                    QueueType.IO, t -> queueManager.enqueue(t, QueueType.CPU), logger, i));
        }

        var ioPhase = new CompositePhaseDispatcher(
                ioProcessors,
                queueManager,
                this.ioModel,
                QueueType.IO);

        // Register with queue manager
        if (queueManager instanceof CentralQueueManager) {
            ((CentralQueueManager) queueManager).registerPhaseDispatcher(ioPhase);
        }

        // CPU completion callback - transaction is already processed, just needs completion logic
        Consumer<Transaction> cpuComplete = t -> executor.complete(t);
        Consumer<Transaction> rollbackComplete = t -> {
            TransactionScheduler transactionScheduler = queueManager instanceof CentralQueueManager centralQueue
                    ? centralQueue.getTransactionScheduler()
                    : null;
            if (transactionScheduler == null) {
                throw new IllegalStateException("Rollback completion requires TransactionScheduler");
            }
            long currentTime = queueManager instanceof CentralQueueManager centralQueue
                    ? centralQueue.getCurrentTime()
                    : 0L;
            new TransactionGroupAbortCoordinator().completeRollback(
                    t.getGroup(),
                    queueManager,
                    txnCoord.getLockManager(),
                    transactionScheduler,
                    currentTime,
                    logger,
                    this.intake == null ? null : this.intake.getCapacityWaitManager());
        };
        // Precedence: the system property still wins (it predates the parameter and some
        // diagnostics pass it), then the RECORDED parameter, then the historical derived value.
        // Prefer maxConcurrentRunning in experiments: the property reaches neither
        // EffectiveConfigHasher nor copyBuilder nor any output row, so runs made under it cannot
        // afterwards be told apart from capped runs.
        int admissionCapacity = Boolean.getBoolean(DISABLE_MPL_LIMIT_PROPERTY)
                ? Integer.MAX_VALUE
                : (cfg.maxConcurrentRunning != null ? cfg.maxConcurrentRunning : cfg.numCpuProcessors);

        // Create CPU processors
        // See the IO pool above for why this must happen before the pool is built.
        if (queueManager instanceof CentralQueueManager centralQueueManagerForCpu) {
            centralQueueManagerForCpu.initPooledQueues(QueueType.CPU, cfg.numCpuProcessors);
        }
        var cpuProcessors = new ArrayList<SimulatedProcessorUnit>();
        for (int i = 0; i < cfg.numCpuProcessors; i++) {
            cpuProcessors.add(new SimulatedProcessorUnit(
                    QueueType.CPU,
                    QueueType.ROLLBACK,
                    // ROLLBACK IS NOT GATED ON ADMISSION CAPACITY, and must not be. A gate of
                    //     max(0, admissionCapacity - count(RUNNING))
                    // livelocks: rollback is the PRIORITY queue on CPU units, so once the system
                    // saturates (RUNNING == admissionCapacity) the gate returns 0 and no rollback
                    // can start. An aborted transaction then sits in ROLLBACK indefinitely -
                    // measured at 200,293 consecutive steps with queueSize=1 - stays ABORTED, and
                    // is eventually dispatched from another queue, surfacing as "Cannot assign
                    // transaction ... in state ABORTED". The crash is the symptom; the starvation
                    // is the fault, and it bites exactly when aborts are most likely: under
                    // saturation.
                    //
                    // Removing the gate cannot breach the cap, because rollback is CLEANUP for
                    // work already admitted and the RESTART it leads to re-enters through
                    // admission on its own: TransactionGroupAbortCoordinator.restartTransaction
                    // builds a new group and calls enqueueWithBackoff -> abortedBackoffQueue, and
                    // AbstractAdmissionController.processBackoffQueues drains that into
                    // QueueType.PENDING ("go back to PENDING (a fresh restart)"), where the
                    // admission cap applies. Cleanup unbounded, admission bounded - the gate was
                    // conflating the two.
                    //
                    // null = no capacity limit (see SimulatedProcessorUnit.canStartPriorityWork).
                    null,
                    cpuComplete,
                    rollbackComplete,
                    logger,
                    i));
        }

        var cpuPhase = new CompositePhaseDispatcher(
                cpuProcessors,
                queueManager,
                this.ioModel,
                QueueType.CPU);

        // Register with queue manager
        if (queueManager instanceof CentralQueueManager) {
            ((CentralQueueManager) queueManager).registerPhaseDispatcher(cpuPhase);
        }

        PhaseDispatcher reconciliationPhase =
                modeAssembler.createReconciliationPhase(
                        cfg,
                        queueManager,
                        this.ioModel,
                        this.dirtySetManager,
                        logger,
                        cpuComplete);

        // Create combined downstream dispatcher for LOCK + IO + CPU + RECONCILIATION phases
        var phases = new ArrayList<PhaseDispatcher>();
        phases.add(lockPhase);
        phases.add(ioPhase);
        phases.add(cpuPhase);
        if (reconciliationPhase != null) {
            phases.add(reconciliationPhase);
        }
        var downstreamPhases = new PhaseDispatcherGroup(phases);
        
        this.intake = modeAssembler.createAdmissionController(
                scheduler,
                processor,
                this.ioModel,
                logger,
                downstreamPhases,
                maxConcurrent,
                queueManager,
                admissionWaitManager);
        txnCoord.setCapacityWaitManager(this.intake.getCapacityWaitManager());
        this.intake.getCapacityWaitManager().setAdmissionBatchSize(cfg.admissionBatchSize);
        this.intake.getCapacityWaitManager().setReadyWaiterOrder(cfg.readyWaiterOrder);
        router.setCuQueuePolicy(cfg.cuQueuePolicy);

        // Single source of truth for the locking protocol (see LockingProtocol's javadoc) - the
        // same cfg.lockingProtocol DefaultLockingBundle already uses to pick the
        // TransactionLockCoordinator subclass. Only ACIDAdmissionController's
        // tryAdmitWithLockCheck() cares (BASE mode has no C2PL/S2PL concept).
        if (this.intake instanceof ACIDAdmissionController acidIntake) {
            acidIntake.setLockingProtocol(cfg.lockingProtocol);
        }

        // Set processor capacity for admission control. Experiments can disable the MPL
        // cap to isolate processor throughput from admission-level concurrency limits.
        this.intake.setProcessorCapacity(admissionCapacity);

        // Wire lifecycle manager to intake dispatcher for admission control
        this.lifecycle.setIntakeDispatcher(this.intake);

        // Create final dispatcher group including all phases
        var allPhases = new ArrayList<PhaseDispatcher>();
        allPhases.add(this.intake);
        allPhases.add(lockPhase);
        allPhases.add(ioPhase);
        allPhases.add(cpuPhase);
        if (reconciliationPhase != null) {
            allPhases.add(reconciliationPhase);
        }
        this.dispatcher = new PhaseDispatcherGroup(allPhases);
    }

    @Override public IOModel ioModel() { return ioModel; }
    @Override public TransactionPhaseInitializer initializer() { return initializer; }
    @Override public TransactionProcessor processor() { return processor; }
    @Override public PhaseDispatcher dispatcher() { return dispatcher; }
    @Override public TransactionExecutor executor() { return executor; }
    @Override public TransactionLifecycleManager lifecycle() { return lifecycle; }
    
    public DirtyStateTracker getDirtySetManager() { return dirtySetManager; }
    
    @Override
    public AbstractAdmissionController intakeDispatcher() {
        return intake;
    }
}
