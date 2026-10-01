package de.tum.tssimulator.locking.policy;

import java.util.Set;

import de.tum.tssimulator.core.Transaction;
import de.tum.tssimulator.scheduling.TransactionScheduler;

/**
 * No-Wait Policy:
 * If the lock is held by another transaction, the requester immediately aborts.
 * This effectively prevents deadlocks by disallowing waiting entirely.
 */
public class NoWaitPolicy implements WaitPolicyStrategy {
    private TransactionScheduler scheduler;

    public NoWaitPolicy(TransactionScheduler scheduler) {
        this.scheduler = scheduler;
    }

    public void setScheduler(TransactionScheduler scheduler) {
        this.scheduler = scheduler;
    }

    @Override
    public LockDecision evaluate(Transaction requester, Set<Transaction> holders) {
        if (!holders.isEmpty()) {
            if (scheduler == null) {
                throw new IllegalStateException("Scheduler not wired in NoWaitPolicy");
            }
            return new HardDenyDecision(scheduler);
        }

        return new GrantedDecision();
    }
}

