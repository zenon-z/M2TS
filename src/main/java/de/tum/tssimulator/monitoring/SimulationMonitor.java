package de.tum.tssimulator.monitoring;

import de.tum.tssimulator.core.TransactionState;
import de.tum.tssimulator.core.TransactionStateCounter;
import de.tum.tssimulator.execution.AdmissionWaitManager;
import de.tum.tssimulator.locking.core.LockManager;
import de.tum.tssimulator.locking.impl.SS2PLLockManager;
import de.tum.tssimulator.monitoring.RuntimeHealthSnapshot;
import de.tum.tssimulator.queue.QueueManager;
import de.tum.tssimulator.queue.QueueType;
import de.tum.tssimulator.scheduling.Scheduler;
import de.tum.tssimulator.scheduling.TransactionScheduler;

public class SimulationMonitor {
    private final QueueManager queueManager;
    private final Scheduler scheduler;
    private final TransactionScheduler txnScheduler;
    private final AdmissionWaitManager admissionWaitManager;
    // TGS Postgres-anchor comparison: unwrapped once at construction (not per-snapshot - the
    // BudgetedLockManagerDecorator wrapping is stable for the run) - null for BASE mode or any
    // wiring that doesn't use SS2PLLockManager, in which case locksHeldCount reads 0.
    private final SS2PLLockManager ss2plLockManager;

    /** Legacy constructor (no admission-waiter/locks-held visibility) - kept for any caller that
     * doesn't have an AdmissionWaitManager/LockManager handy; admissionWaiterCount and
     * locksHeldCount read as 0 in snapshots from this path, same as before those fields existed. */
    public SimulationMonitor(QueueManager queueManager, Scheduler scheduler, TransactionScheduler txnScheduler) {
        this(queueManager, scheduler, txnScheduler, null, null);
    }

    public SimulationMonitor(QueueManager queueManager, Scheduler scheduler, TransactionScheduler txnScheduler,
            AdmissionWaitManager admissionWaitManager) {
        this(queueManager, scheduler, txnScheduler, admissionWaitManager, null);
    }

    public SimulationMonitor(QueueManager queueManager, Scheduler scheduler, TransactionScheduler txnScheduler,
            AdmissionWaitManager admissionWaitManager, LockManager lockManager) {
        this.queueManager = queueManager;
        this.scheduler = scheduler;
        this.txnScheduler = txnScheduler;
        this.admissionWaitManager = admissionWaitManager;
        this.ss2plLockManager = lockManager == null ? null : SS2PLLockManager.unwrap(lockManager);
    }

    /**
     * Intentionally silent. Called once per run from {@code Simulator}'s finally block, where
     * {@code SimulationLogger.summary()} already emits the end-of-run figures; a status dump
     * here would duplicate them.
     */
    public void printStatus() {
    }

    public int totalLiveTransactions() {
        return queueManager.size(QueueType.PENDING)
             + queueManager.size(QueueType.BLOCKED_TXN);
    }

    public RuntimeHealthSnapshot snapshot(long virtualTime) {
        return new RuntimeHealthSnapshot(
                virtualTime,
                queueManager.size(QueueType.PENDING),
                queueManager.size(QueueType.BLOCKED_TXN),
                queueManager.size(QueueType.READY_TXN),
                queueManager.size(QueueType.LOCK),
                queueManager.size(QueueType.IO),
                queueManager.size(QueueType.CPU),
                queueManager.size(QueueType.ROLLBACK),
                queueManager.size(QueueType.RECONCILIATION),
                queueManager.size(QueueType.AWAITING_USER_ACTION),
                queueManager.size(QueueType.WAITING_FOR_GROUP),
                TransactionStateCounter.getCount(TransactionState.PENDING),
                TransactionStateCounter.getCount(TransactionState.RUNNING),
                TransactionStateCounter.getCount(TransactionState.BLOCKED),
                TransactionStateCounter.getCount(TransactionState.READY),
                TransactionStateCounter.getCount(TransactionState.AWAITING_USER_ACTION),
                TransactionStateCounter.getCount(TransactionState.COMPLETED),
                TransactionStateCounter.getCount(TransactionState.ABORTED),
                admissionWaitManager != null ? admissionWaitManager.getTotalWaiterCount() : 0,
                ss2plLockManager != null ? ss2plLockManager.getLocksHeld() : 0);
    }
}
