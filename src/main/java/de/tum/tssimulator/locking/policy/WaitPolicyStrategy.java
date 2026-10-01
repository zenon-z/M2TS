package de.tum.tssimulator.locking.policy;

import java.util.Set;

import de.tum.tssimulator.core.Transaction;

public interface WaitPolicyStrategy {
    LockDecision evaluate(Transaction requester, Set<Transaction> holders);
}
