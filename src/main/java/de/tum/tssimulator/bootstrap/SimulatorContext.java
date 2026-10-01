package de.tum.tssimulator.bootstrap;

import java.util.List;
import java.util.Objects;

import de.tum.tssimulator.bookkeeping.BookkeeperFactory;
import de.tum.tssimulator.bookkeeping.CprGraphMetrics;
import de.tum.tssimulator.bookkeeping.SchemaCprFactory;
import de.tum.tssimulator.config.ConcurrencyModel;
import de.tum.tssimulator.config.SimulatorConfig;
import de.tum.tssimulator.core.Model;
import de.tum.tssimulator.core.TransactionFactory;
import de.tum.tssimulator.core.TransactionIdGenerator;
import de.tum.tssimulator.cpr.CprSchema;
import de.tum.tssimulator.cpr.CprSchemaBuilder;
import de.tum.tssimulator.execution.BookkeeperOrchestrator;
import de.tum.tssimulator.execution.PhaseDispatcher;
import de.tum.tssimulator.execution.PhaseDispatcherGroup;
import de.tum.tssimulator.execution.SimulatedProcessorUnit;
import de.tum.tssimulator.execution.TransactionExecutor;
import de.tum.tssimulator.execution.TransactionProcessor;
import de.tum.tssimulator.lifecycle.TransactionLifecycleManager;
import de.tum.tssimulator.locking.core.GlobalLockBudget;
import de.tum.tssimulator.model.IOModel;
import de.tum.tssimulator.monitoring.SimulationLogger;
import de.tum.tssimulator.monitoring.SimulationMonitor;
import de.tum.tssimulator.queue.CentralQueueManager;
import de.tum.tssimulator.queue.QueuePort;
import de.tum.tssimulator.scheduling.Scheduler;
import de.tum.tssimulator.scheduling.TransactionScheduler;
import de.tum.tssimulator.state.DirtySetManager;
import de.tum.tssimulator.state.DirtyStateTracker;
import de.tum.tssimulator.state.TransactionGroupRegistry;

public class SimulatorContext {
    // === Fields ===
    public final BookkeeperFactory bookkeeperFactory;
    public final List<SimulatedProcessorUnit> ioProcessors;
    public final List<SimulatedProcessorUnit> cpuProcessors;
    private final SimulationLogger logger;
    private final SimulatorConfig config;
    public final IOModel ioModel;
    private final SimulationMonitor monitor;
    private final TransactionGroupRegistry registry;
    private final SchedulingBundle schedulingBundle;
    private final LockingBundle lockingBundle;
    private final ExecutionBundle executionBundle;
    private final DirtyStateTracker dirtySetManager;  // NEW: For BASE mode dirty set tracking
    private final RuntimeServices runtimeServices;
    
    // Virtual time accessible to all components
    public long virtualTime = 0;

    // wall-clock cap for FIXED_TRANSACTION mode: Long.MAX_VALUE = disabled
    // (default, every pre-existing caller). Set to System.currentTimeMillis() + budget by the
    // caller before Simulator.run() to cap a run's REAL elapsed time rather than its virtual
    // time or completion count - FixedTransactionStepStrategy checks this every step. Now a pure
    // runaway guard (correction: a run capped on WALL-CLOCK time censors at a point correlated
    // with its own slowness, biasing Kaplan-Meier - see simulatedTimeCap below for the
    // KM-safe cap) - a run that hits this is tagged TerminatedBy.WALL_CLOCK_RUNAWAY and should be
    // excluded from statistical analysis, not folded into it.
    public long wallClockDeadlineMs = Long.MAX_VALUE;

    // caps a run's SIMULATED (virtual) time instead of its real elapsed time -
    // identical across every seed/config that sets the same budget, so every run's Kaplan-Meier
    // right-censoring happens at the exact same point in its own timeline regardless of how slow
    // it was to get there. This is the KM-safe cap; wallClockDeadlineMs above is the (KM-unsafe)
    // runaway guard. Long.MAX_VALUE = disabled (default, every pre-existing caller).
    public long simulatedTimeCap = Long.MAX_VALUE;
    
    private SimulatorContext(Builder builder) {
//        this.lockManager = builder.lockManager;
        this.bookkeeperFactory = builder.bookkeeperFactory;
        this.ioProcessors = builder.ioProcessors;
        this.cpuProcessors = builder.cpuProcessors;
        this.runtimeServices = builder.runtimeServices;
        this.logger = runtimeServices.logger();
        this.config = runtimeServices.config();
        this.ioModel = builder.ioModel;
        this.monitor = runtimeServices.monitor();
        this.registry = runtimeServices.registry();
        this.schedulingBundle = builder.schedulingBundle;
        this.lockingBundle = builder.lockingBundle;
        this.executionBundle = builder.executionBundle;
        this.dirtySetManager = runtimeServices.dirtyState();
    }

    public static class Builder {
        private BookkeeperFactory bookkeeperFactory;
        private List<SimulatedProcessorUnit> ioProcessors;
        private List<SimulatedProcessorUnit> cpuProcessors;
        private IOModel ioModel;
        private RuntimeServices runtimeServices;

        // Optional bundles for decoupled wiring
        private LockingBundle lockingBundle;
        private SchedulingBundle schedulingBundle;
        private ExecutionBundle executionBundle;

        public Builder bookkeeperFactory(BookkeeperFactory bf) { this.bookkeeperFactory = bf; return this; }
        public Builder ioProcessors(List<SimulatedProcessorUnit> io) { this.ioProcessors = io; return this; }
        public Builder cpuProcessors(List<SimulatedProcessorUnit> cpu) { this.cpuProcessors = cpu; return this; }
        public Builder ioModel(IOModel ioModel) { this.ioModel = ioModel; return this; }
        public Builder with(RuntimeServices services) { this.runtimeServices = services; return this; }

        // Bundle hooks
        public Builder with(LockingBundle b) { this.lockingBundle = b; return this; }
        public Builder with(SchedulingBundle b) { this.schedulingBundle = b; return this; }
        public Builder with(ExecutionBundle b) { this.executionBundle = b; return this; }
        
        public SimulatorContext build() {
            this.runtimeServices = Objects.requireNonNull(this.runtimeServices,
                "RuntimeServices must be provided via Builder.with(RuntimeServices)");
            if (this.executionBundle != null && this.ioModel == null) {
                this.ioModel = this.executionBundle.ioModel();
            }
            return new SimulatorContext(this);
        }

    }

    public SchedulingBundle scheduling() {
        return schedulingBundle;
    }

    public LockingBundle locking() {
        return lockingBundle;
    }

    public ExecutionBundle execution() {
        return executionBundle;
    }

    public SimulatorConfig config() {
        return runtimeServices.config();
    }

    public SimulationLogger logger() {
        return runtimeServices.logger();
    }

    public SimulationMonitor monitor() {
        return runtimeServices.monitor();
    }

    public TransactionGroupRegistry registry() {
        return runtimeServices.registry();
    }

    public DirtyStateTracker dirtyState() {
        return runtimeServices.dirtyState();
    }

    public ExperimentRuntimeSnapshot experimentSnapshot() {
        return ExperimentRuntimeSnapshotFactory.capture(this);
    }

    @Deprecated
    public IOModel ioModel() {
        return execution().ioModel();
    }

    @Deprecated
    public QueuePort queues() {
        return scheduling().queues();
    }

    @Deprecated
    public Scheduler scheduler() {
        return scheduling().scheduler();
    }

    @Deprecated
    public TransactionScheduler txnScheduler() {
        return scheduling().txnScheduler();
    }

    @Deprecated
    public GlobalLockBudget globalBudget() {
        return locking().budget();
    }

    @Deprecated
    public TransactionProcessor processor() {
        return execution().processor();
    }

    @Deprecated
    public PhaseDispatcher dispatcher() {
        return execution().dispatcher();
    }

    @Deprecated
    public TransactionExecutor executor() {
        return execution().executor();
    }

    @Deprecated
    public TransactionLifecycleManager lifecycle() {
        return execution().lifecycle();
    }

    public static SimulatorContext create(SimulatorConfig config) {
        // Build via default bundles to centralize wiring and expose extension points
        TransactionIdGenerator idGen = new TransactionIdGenerator();

        // CprSchema is deterministic from config and model pool, so we can compute it (and the
        // BASE dirty-set denominator below) before constructing the logger. Built before
        // txFactory, not after, because the D-oracle arm (config.oracleClosurePreclaim) needs
        // this exact same schema instance wired into TransactionFactory - see its javadoc.
        CprSchema cprSchema = new CprSchemaBuilder(
                config.modelPool, config.cprTopologyMode, config.semanticOverlap,
                config.avgCprsPerResource, config.maxCprsPerResource, config.semanticOverlapSeed,
                config.cprInitialAttractiveness, config.cprAcyclicTotalOrder)
            .build();

        TransactionFactory txFactory = new TransactionFactory(idGen, config.transactionSamplingSeed,
                config.oracleClosurePreclaim ? cprSchema : null);

        RuntimeModeAssembler modeAssembler = RuntimeModeAssemblers.forConfig(config);

        BookkeeperFactory bkFactory = new SchemaCprFactory(cprSchema, config.concurrencyModel,
                config.writeOnlyCprSources, config.cuOperationType);

        int dirtySetDenominatorResources = config.totalResources;
        if (config.concurrencyModel == ConcurrencyModel.BASE) {
            int totalProperties = config.modelPool.stream().mapToInt(Model::propertyCount).sum();
            CprGraphMetrics graphMetrics = CprGraphMetrics.from(cprSchema, totalProperties);
            // identity correspondence: inDegreeNonzeroResources is a property-level count that
            // scales by instancePoolSize to become the resource-level denominator - the same
            // invariant (one shared instancePoolSize across the whole model pool) already holds
            // via ModelFactory, so this fold-out is exact, not approximate.
            dirtySetDenominatorResources = Math.max(1, graphMetrics.inDegreeNonzeroResources() * config.instancePoolSize);
        }

        SimulationLogger logger = new SimulationLogger(
            SimulationLogDestinationFactory.create(),
            config.totalResources,
            dirtySetDenominatorResources,
            // One dirty-set sample per simulated SECOND (see FixedTimeStepStrategy), so the
            // array is sized by duration and is slice-independent. The recovery-latency and
            // lock-wait arrays derive from this but GROW by doubling, so a small seed is safe.
            SimulatorConfig.dirtySampleCount(
                    config.simulationDurationSeconds) + 1);
        TransactionGroupRegistry registry = new TransactionGroupRegistry(logger);

        // 1. CREATE IOModel FIRST (before anything that needs it)
        IOModel ioModel = new IOModel(config);

        // 2. Scheduling (queues, scheduler, txnScheduler) - WITH IOModel
        var scheduling = new DefaultSchedulingBundle(config, txFactory, registry, ioModel);
        CentralQueueManager queueManager = (CentralQueueManager) scheduling.queues();
        
        // Wire TransactionScheduler to QueueManager for abort-restart functionality
        queueManager.setTransactionScheduler(scheduling.txnScheduler());

        // Locking (budget, lock manager, coordinators)
        var locking = new DefaultLockingBundle(config, queueManager, scheduling.scheduler(), logger);
        boolean hasDedicatedReconcilers = modeAssembler.hasDedicatedReconcilers(config);
        
        // Create orchestrator with simplified constructor (only needs config for ON_RESTRICT)
        var orchestrator = new BookkeeperOrchestrator(config);

        DirtyStateTracker dirtySetManager = modeAssembler.createDirtySetManager();
        
        var coordinator = modeAssembler.createBookkeeperCoordinator(
            bkFactory,
            logger,
            registry,
            orchestrator,
            dirtySetManager,
            null,  // phaseInitializer - will be set after execution bundle created
            hasDedicatedReconcilers,
            () -> queueManager.getCurrentTime()  // virtualTimeClock supplier from authoritative source
        );
        
        var router = modeAssembler.createBookkeeperRouter(
            logger,
            queueManager,
            hasDedicatedReconcilers
        );

        // Wire locking bundle BEFORE creating execution bundle (Issue #2 fix)
        // The locking bundle needs registry and scheduler wired before it can provide txnCoordinator
        locking.wireRegistry(registry);
        locking.wireScheduler(scheduling.txnScheduler());

        // Execution (ioModel, initializer, processor, dispatcher, executor)
        // Pass IOModel that was created earlier (Fix A: Phase Prep Centralization)
        var execution = new DefaultExecutionBundle(
            config,
            queueManager,
            scheduling.scheduler(),
            locking.txnCoordinator(),
            coordinator,  // Pass coordinator instead of creator
            router,       // Pass router
            registry,
            logger,
            ioModel,      // Pass IOModel created earlier
            dirtySetManager,
            locking.getAdmissionWaitManager()
        );
        
        // Now wire phaseInitializer back into coordinator (BASE mode only)
        coordinator.setPhaseInitializer(execution.initializer());
        
        // Wire latency recorder from logger to dirty set manager (BASE mode only)
        if (dirtySetManager instanceof DirtySetManager concreteDirtySetManager) {
            concreteDirtySetManager.setLatencyRecorder(logger.metrics()::recordRecoveryLatency);
        }
        
        // Wire dispatcher to registry for clearing transactions from processors during state transitions
        if (execution.dispatcher() instanceof PhaseDispatcherGroup) {
            registry.setDispatcher((PhaseDispatcherGroup) execution.dispatcher());
        }
        
        // Wire lifecycle callbacks after execution bundle created
        locking.wireLifecycleCallbacks(execution.lifecycle());

        // Monitor uses scheduler and txnScheduler, plus the AdmissionWaitManager for
        // admission-waiter visibility (see RuntimeHealthSnapshot.admissionWaiterCount's javadoc)
        // and the LockManager for sampled-H visibility (see locksHeldCount's javadoc - the
        // Postgres-anchor comparison quantity).
        SimulationMonitor monitor = new SimulationMonitor(queueManager, scheduling.scheduler(),
                scheduling.txnScheduler(), locking.getAdmissionWaitManager(), locking.lockManager());

        RuntimeServices runtimeServices = new DefaultRuntimeServices(
            config,
            logger,
            monitor,
            registry,
            dirtySetManager);

        return new SimulatorContext.Builder()
            .with(runtimeServices)
            .with(scheduling)
            .with(locking)
            .with(execution)
            .bookkeeperFactory(bkFactory)
            .build();
    }
}
