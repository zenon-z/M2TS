package de.tum.tssimulator.bootstrap;

import de.tum.tssimulator.config.SimulatorConfig;
import de.tum.tssimulator.execution.AdmissionWaitManager;
import de.tum.tssimulator.lifecycle.BlockedTransactionRetrier;
import de.tum.tssimulator.locking.coordinator.C2PLTransactionLockCoordinator;
import de.tum.tssimulator.locking.coordinator.S2PLTransactionLockCoordinator;
import de.tum.tssimulator.locking.coordinator.TransactionLockCoordinator;
import de.tum.tssimulator.locking.core.GlobalLockBudget;
import de.tum.tssimulator.locking.core.LockGranularityMapper;
import de.tum.tssimulator.locking.core.LockManager;
import de.tum.tssimulator.locking.core.LockWaitTracker;
import de.tum.tssimulator.locking.impl.BudgetedLockManagerDecorator;
import de.tum.tssimulator.locking.impl.MapBackedLockWaitTracker;
import de.tum.tssimulator.locking.impl.SS2PLLockManager;
import de.tum.tssimulator.locking.policy.AdaptiveWaitPolicy;
import de.tum.tssimulator.locking.policy.CautiousWaitingPolicy;
import de.tum.tssimulator.locking.policy.DeadlockStrategy;
import de.tum.tssimulator.locking.policy.LockingProtocol;
import de.tum.tssimulator.locking.policy.NoWaitPolicy;
import de.tum.tssimulator.locking.policy.OnlySoftDenyPolicy;
import de.tum.tssimulator.locking.policy.WaitDiePolicy;
import de.tum.tssimulator.locking.policy.WaitPolicyStrategy;
import de.tum.tssimulator.locking.policy.WoundWaitPolicy;
import de.tum.tssimulator.monitoring.SimulationLogger;
import de.tum.tssimulator.queue.CentralQueueManager;
import de.tum.tssimulator.queue.QueueType;
import de.tum.tssimulator.scheduling.Scheduler;
import de.tum.tssimulator.scheduling.TransactionScheduler;
import de.tum.tssimulator.state.TransactionGroupRegistry;

public class DefaultLockingBundle implements LockingBundle {
    // TGS static-locking validation (A3's MAX_WAKEUPS_PER_RELEASE sensitivity slice): override
    // point for AdmissionWaitManager's wakeup batch size B, read once at construction like the
    // repo's other -Dts.* experiment-only toggles (ts.rawRow.warnOnly, ts.metrics.throughputTrace,
    // ts.simulator.disableMplLimit). Unset means the default (AdmissionWaitManager's own
    // DEFAULT_MAX_WAKEUPS_PER_RELEASE = 4) is used, so ordinary runs are unaffected.
    private static final String MAX_WAKEUPS_PER_RELEASE_PROPERTY = "ts.admissionWait.maxWakeupsPerRelease";

    private final SimulatorConfig cfg;

    private final GlobalLockBudget budget;
    private final AdmissionWaitManager admissionWaitManager;
    private final CentralQueueManager queueManager;
    private final SimulationLogger logger;
    private final LockGranularityMapper mapper;
    private final LockWaitTracker waitTracker;

    private TransactionGroupRegistry registry;
    private TransactionScheduler txnScheduler;
    private BlockedTransactionRetrier lifecycle;

    private LockManager lm;
    private TransactionLockCoordinator txnCoord;
    private SS2PLLockManager baseTxn;
    private WaitPolicyStrategy txnPolicy;

    public DefaultLockingBundle(SimulatorConfig cfg, CentralQueueManager queueManager, Scheduler scheduler, SimulationLogger logger) {
        this.cfg = cfg;
        this.budget = new GlobalLockBudget(cfg.totalLocks, cfg.totalResources);
        this.queueManager = queueManager;
        this.logger = logger;
        int maxWakeupsPerRelease = Integer.getInteger(MAX_WAKEUPS_PER_RELEASE_PROPERTY, 4);
        this.admissionWaitManager = new AdmissionWaitManager(queueManager, maxWakeupsPerRelease);
        this.mapper = new LockGranularityMapper(cfg.totalLocks, cfg.modelPool);
        this.waitTracker = new MapBackedLockWaitTracker();

        // Coordinator will be created once we have the lock manager
        this.txnCoord = null;
    }

    @Override
    public void wireLifecycleCallbacks(BlockedTransactionRetrier lifecycle) {
        this.lifecycle = lifecycle;
        // If baseTxn already exists, wire it now
        if (baseTxn != null) {
            baseTxn.setLifecycleManager(lifecycle);
        }
    }

    @Override
    public void wireRegistry(TransactionGroupRegistry registry) {
        this.registry = registry;
        maybeInit();
    }

    @Override
    public void wireScheduler(TransactionScheduler scheduler) {
        this.txnScheduler = scheduler;
        // Coordinator will get scheduler when it's created in maybeInit()
        maybeInit();
    }

    private void maybeInit() {
        if (lm != null)
            return; // Already initialized
        if (registry == null || txnScheduler == null)
            return; // Not ready yet

        // Create policy with all dependencies
        switch (cfg.deadlockStrategy) {
            case NO_WAIT:
                txnPolicy = new NoWaitPolicy(txnScheduler);
                break;

            case WAIT_DIE:
                txnPolicy = new WaitDiePolicy(QueueType.BLOCKED_TXN, txnScheduler);
                break;

            case WOUND_WAIT:
                txnPolicy = new WoundWaitPolicy(queueManager, QueueType.BLOCKED_TXN);
                if (txnPolicy instanceof WoundWaitPolicy) {
                    ((WoundWaitPolicy) txnPolicy).setScheduler(txnScheduler);
                }
                break;

            case ADAPTIVE:
                txnPolicy = new AdaptiveWaitPolicy(
                        queueManager, waitTracker, registry, txnScheduler);
                break;

            case ONLY_SOFT_DENY:
                txnPolicy = new OnlySoftDenyPolicy();
                break;

            case CAUTIOUS_WAITING:
            default:
                txnPolicy = new CautiousWaitingPolicy(
                        QueueType.BLOCKED_TXN,
                        registry,
                        waitTracker,
                        txnScheduler);
                break;
        }

        // Create base lock manager
        baseTxn = new SS2PLLockManager(txnPolicy, waitTracker);
        baseTxn.setRegistry(registry);
        baseTxn.setMetricsLogger(logger);
        baseTxn.setAdmissionWaitManager(admissionWaitManager);
        baseTxn.setQueueManager(queueManager);

        if (txnPolicy instanceof WoundWaitPolicy) {
            ((WoundWaitPolicy) txnPolicy).setLockManager(baseTxn);
        } else if (txnPolicy instanceof AdaptiveWaitPolicy) {
            ((AdaptiveWaitPolicy) txnPolicy).setLockManager(baseTxn);
        }

        // Wire lifecycle if it was set before initialization
        if (lifecycle != null) {
            baseTxn.setLifecycleManager(lifecycle);
        }

        // Wrap with budget decorator
        lm = new BudgetedLockManagerDecorator(baseTxn, budget);

        // Now create coordinator with lock manager and scheduler - which concrete subclass
        // depends on cfg.lockingProtocol (see LockingProtocol's javadoc).
        txnCoord = cfg.lockingProtocol == LockingProtocol.S2PL
                ? new S2PLTransactionLockCoordinator(
                        lm, queueManager, logger, mapper, txnScheduler, cfg.operationBatchSize)
                : new C2PLTransactionLockCoordinator(
                        lm, queueManager, logger, mapper, txnScheduler);
        if (cfg.deadlockStrategy == DeadlockStrategy.ONLY_SOFT_DENY) {
            txnCoord.setEnableDeadlockPreventionCascade(false);
        }
    }

    @Override
    public GlobalLockBudget budget() {
        return budget;
    }

    @Override
    public LockManager lockManager() {
        if (lm == null) {
            throw new IllegalStateException(
                    "LockingBundle not fully wired - call wireRegistry() and wireScheduler() first");
        }
        return lm;
    }

    @Override
    public TransactionLockCoordinator txnCoordinator() {
        if (txnCoord == null) {
            throw new IllegalStateException(
                    "LockingBundle not fully wired - call wireRegistry() and wireScheduler() first");
        }
        return txnCoord;
    }

    public AdmissionWaitManager getAdmissionWaitManager() {
        return admissionWaitManager;
    }
}

