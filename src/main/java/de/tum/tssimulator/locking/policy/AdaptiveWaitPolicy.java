package de.tum.tssimulator.locking.policy;

import de.tum.tssimulator.core.Transaction;
import de.tum.tssimulator.execution.AdaptiveConcurrencyManager;
import de.tum.tssimulator.locking.core.LockManager;
import de.tum.tssimulator.locking.core.LockWaitTracker;
import de.tum.tssimulator.queue.QueueManager;
import de.tum.tssimulator.queue.QueueType;
import de.tum.tssimulator.scheduling.TransactionScheduler;
import de.tum.tssimulator.state.TransactionGroupRegistry;
import java.util.Set;

/**
 * A composite policy that delegates to the current active strategy.
 */
public class AdaptiveWaitPolicy implements WaitPolicyStrategy {

    private final NoWaitPolicy noWait;
    private final WaitDiePolicy waitDie;
    private final WoundWaitPolicy woundWait;
    private final CautiousWaitingPolicy cautiousWaiting;
    private final AdaptiveConcurrencyManager manager;

    public AdaptiveWaitPolicy(
            QueueManager queueManager,
            LockWaitTracker waitTracker,
            TransactionGroupRegistry registry,
            TransactionScheduler scheduler) {

        this.manager = AdaptiveConcurrencyManager.getInstance();

        // Initialize all policies with available dependencies
        this.noWait = new NoWaitPolicy(scheduler);
        this.waitDie = new WaitDiePolicy(QueueType.BLOCKED_TXN, scheduler);
        this.woundWait = new WoundWaitPolicy(queueManager, QueueType.BLOCKED_TXN);
        this.cautiousWaiting = new CautiousWaitingPolicy(
                QueueType.BLOCKED_TXN,
                registry,
                waitTracker,
                scheduler);

        // WoundWait needs scheduler too
        this.woundWait.setScheduler(scheduler);
    }

    // Setters for late-binding dependencies
    public void setLockManager(LockManager lm) {
        this.woundWait.setLockManager(lm);
    }

    public void setScheduler(TransactionScheduler scheduler) {
        this.noWait.setScheduler(scheduler);
        this.waitDie.setScheduler(scheduler);
        this.woundWait.setScheduler(scheduler);
        // CautiousWaiting is immutable regarding scheduler, so we assume it's passed in
        // constructor
    }

    @Override
    public LockDecision evaluate(Transaction requester, Set<Transaction> holders) {
        DeadlockStrategy currentStrategy = manager.getCurrentStrategy();

        switch (currentStrategy) {
            case NO_WAIT:
                return noWait.evaluate(requester, holders);
            case WAIT_DIE:
                return waitDie.evaluate(requester, holders);
            case WOUND_WAIT:
                return woundWait.evaluate(requester, holders);
            case CAUTIOUS_WAITING:
            default:
                return cautiousWaiting.evaluate(requester, holders);
        }
    }
}
