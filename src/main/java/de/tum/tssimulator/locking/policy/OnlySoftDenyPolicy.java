package de.tum.tssimulator.locking.policy;

import java.util.Set;

import de.tum.tssimulator.core.Transaction;
import de.tum.tssimulator.queue.QueueType;

public class OnlySoftDenyPolicy implements WaitPolicyStrategy {
    // No dependencies needed

    public OnlySoftDenyPolicy() {
        // No initialization needed
    }

    @Override
    public LockDecision evaluate(Transaction requester, Set<Transaction> holders) {
        return new SoftDenyDecision(QueueType.BLOCKED_TXN);
    }
}

