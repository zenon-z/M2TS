package test.java.locking;

import java.util.Map;
import java.util.Set;

import de.tum.tssimulator.core.Transaction;
import de.tum.tssimulator.core.TransactionGroup;
import de.tum.tssimulator.locking.core.LockManager;
import de.tum.tssimulator.locking.core.LockType;
import de.tum.tssimulator.locking.policy.GrantedDecision;
import de.tum.tssimulator.locking.policy.LockDecision;

public class AlwaysGrantLockManager implements LockManager {
    @Override
    public LockDecision requestLock(Transaction t, String resourceId, LockType type) {
        return new GrantedDecision();
    }

    @Override
    public void releaseLocks(Transaction t) {
        // no-op
    }

    @Override
    public void abortTransactionGroup(TransactionGroup group) {
        // no-op
    }

    @Override
    public boolean causesDeadlock(Transaction t, String blockedOn) {
        return false;
    }
    
    @Override
    public boolean tryAcquireAllLocks(Transaction t, Map<String, LockType> requested) {
        return true;
    }
    
    @Override
    public String checkLocksAvailable(Transaction t, Map<String, LockType> requested) {
        return null; // All locks available
    }
    
    @Override
    public void registerWaiter(Transaction txn, String lockId) {
        // no-op
    }
    
    @Override
    public Set<Transaction> getWaitersForTransaction(Transaction holder) {
        return Set.of();
    }
}
