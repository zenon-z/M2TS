package de.tum.tssimulator.locking.policy;

import java.util.Set;

import de.tum.tssimulator.core.Transaction;
import de.tum.tssimulator.queue.QueueType;
import de.tum.tssimulator.scheduling.TransactionScheduler;

/**
 * Wait-Die deadlock prevention: older transactions wait, younger ones abort.
 */
public class WaitDiePolicy implements WaitPolicyStrategy {
    private final SoftDenyDecision waitDecision;
    private TransactionScheduler scheduler;

    public WaitDiePolicy(QueueType blockQueue, TransactionScheduler scheduler) {
        this.waitDecision = new SoftDenyDecision(blockQueue);
        this.scheduler = scheduler;
    }

    public void setScheduler(TransactionScheduler scheduler) {
        this.scheduler = scheduler;
    }

    @Override
    public LockDecision evaluate(Transaction requester, Set<Transaction> holders) {
        long requesterTimestamp = requester.getTimestamp();
        boolean requesterYoungerThanAnyHolder = holders.stream()
                .anyMatch(holder -> requesterTimestamp > holder.getTimestamp());

        if (requesterYoungerThanAnyHolder) {
            if (scheduler == null) {
                throw new IllegalStateException("Scheduler not wired in WaitDiePolicy");
            }
            return new HardDenyDecision(scheduler);
        }
        return waitDecision;
    }
}

