package de.tum.tssimulator.locking.policy;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import de.tum.tssimulator.core.Transaction;
import de.tum.tssimulator.locking.core.LockManager;
import de.tum.tssimulator.queue.CentralQueueManager;
import de.tum.tssimulator.queue.QueueManager;
import de.tum.tssimulator.queue.QueueType;
import de.tum.tssimulator.scheduling.TransactionScheduler;

/**
 * Wound-Wait Policy:
 * - If Requester is OLDER than Holder: Requester WOUNDS Holder (Holder Aborts),
 * Requester WAITS/RETIRES.
 * - If Requester is YOUNGER than Holder: Requester WAITS.
 * * Timestamp logic: Smaller timestamp = Older transaction.
 */
public class WoundWaitPolicy implements WaitPolicyStrategy {
    private final QueueManager queueManager;
    private LockManager lockManager;
    private TransactionScheduler scheduler;
    private final QueueType waitQueue;

    public WoundWaitPolicy(QueueManager queueManager) {
        this(queueManager, QueueType.BLOCKED_TXN);
    }

    public WoundWaitPolicy(QueueManager queueManager, QueueType waitQueue) {
        this.queueManager = queueManager;
        this.waitQueue = waitQueue;
    }

    public void setLockManager(LockManager lockManager) {
        this.lockManager = lockManager;
    }

    public void setScheduler(TransactionScheduler scheduler) {
        this.scheduler = scheduler;
    }

    @Override
    public LockDecision evaluate(Transaction requester, Set<Transaction> holders) {
        if (lockManager == null || scheduler == null) {
            throw new IllegalStateException("WoundWaitPolicy missing dependencies: LockManager or Scheduler is null");
        }

        long requesterTs = requester.getTimestamp();
        boolean woundedSomeone = false;
        long currentTime = resolveCurrentTime();

        // Create a defensive copy to avoid ConcurrentModificationException
        // because abortAll() triggers releaseLocks() which modifies the original
        // holders set
        List<Transaction> holdersCopy = new ArrayList<>(holders);

        for (Transaction holder : holdersCopy) {
            if (requesterTs < holder.getTimestamp()) {
                if (holder.getGroup() != null && lockManager != null && scheduler != null) {
                    holder.getGroup().abortAll(queueManager, lockManager, scheduler, currentTime, null, null);
                    woundedSomeone = true;
                }
            }
        }

        // Regardless of whether we wounded someone or not:
        // 1. If we wounded: The lock isn't free instantly (cleanup takes time), so we
        // wait (SoftDeny).
        // Next time we retry, the lock should be free.
        // 2. If we didn't wound (we are younger): We wait (SoftDeny).

        return new SoftDenyDecision(waitQueue);
    }

    private long resolveCurrentTime() {
        if (queueManager instanceof CentralQueueManager) {
            return ((CentralQueueManager) queueManager).getCurrentTime();
        }
        return 0L;
    }
}

