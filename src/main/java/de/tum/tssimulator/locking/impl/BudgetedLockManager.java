package de.tum.tssimulator.locking.impl;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import de.tum.tssimulator.core.Transaction;
import de.tum.tssimulator.core.TransactionGroup;
import de.tum.tssimulator.execution.AdmissionWaitManager;
import de.tum.tssimulator.locking.core.AdmissionAwareLockManager;
import de.tum.tssimulator.locking.core.GlobalLockBudget;
import de.tum.tssimulator.locking.core.LockManager;
import de.tum.tssimulator.locking.core.LockStateView;
import de.tum.tssimulator.locking.core.LockType;
import de.tum.tssimulator.locking.policy.LockDecision;

public class BudgetedLockManager implements LockManager, AdmissionAwareLockManager, LockStateView {
    private final LockManager inner;
    private final GlobalLockBudget budget;
    private final Function<Transaction, Integer> tokenEstimator;
    private final Map<Transaction, Integer> txnTokenMap = new HashMap<>();

    public BudgetedLockManager(LockManager inner,
                               GlobalLockBudget budget,
                               Function<Transaction, Integer> tokenEstimator) {
        this.inner = inner;
        this.budget = budget;
        this.tokenEstimator = tokenEstimator;
    }

    @Override
    public LockDecision requestLock(Transaction t, String res, LockType type) {
        boolean isFirst = isFirstLock(t, res); // define however you want

        if (isFirst) {
            int tokens = tokenEstimator.apply(t);
            boolean success = budget.tryAcquire(tokens);
            if (!success) {
                // Let inner policy decide what to return (use dummy set)
                return inner.requestLock(t, res, type); // will call waitPolicy internally
            }
            txnTokenMap.put(t, tokens);
        }

        return inner.requestLock(t, res, type);
    }

    @Override
    public void releaseLocks(Transaction t) {
        inner.releaseLocks(t);
        Integer tokens = txnTokenMap.remove(t);
        if (tokens != null) {
            budget.release(tokens);
        }
    }

    @Override
    public void abortTransactionGroup(TransactionGroup group) {
        inner.abortTransactionGroup(group);
    }

    @Override
    public boolean causesDeadlock(Transaction t, String blockedOn) {
        return inner.causesDeadlock(t, blockedOn);
    }
    
    @Override
    public boolean tryAcquireAllLocks(Transaction t, Map<String, LockType> requested) {
        // Try to acquire budget for all locks
        if (!budget.tryAcquire(requested.size())) {
            return false;
        }
        
        // Try atomic acquisition
        boolean success = inner.tryAcquireAllLocks(t, requested);
        
        if (success) {
            txnTokenMap.put(t, requested.size());
        } else {
            // Refund budget if acquisition failed
            budget.release(requested.size());
        }
        
        return success;
    }
    
    @Override
    public String checkLocksAvailable(Transaction t, Map<String, LockType> requested) {
        // Check budget first (without acquiring)
        if (budget.getAvailableTokens() < requested.size()) {
            return "BUDGET_INSUFFICIENT"; // Special marker for budget limit
        }
        
        // Delegate to underlying lock manager
        return inner.checkLocksAvailable(t, requested);
    }
    
    /**
     * Set the admission wait manager on the delegate if supported.
     */
    @Override
    public void setAdmissionWaitManager(AdmissionWaitManager manager) {
        if (inner instanceof AdmissionAwareLockManager admissionAwareLockManager) {
            admissionAwareLockManager.setAdmissionWaitManager(manager);
        }
    }
    
    @Override
    public void registerWaiter(Transaction txn, String lockId) {
        inner.registerWaiter(txn, lockId);
    }
    
    @Override
    public Set<Transaction> getWaitersForTransaction(Transaction holder) {
        return inner.getWaitersForTransaction(holder);
    }

    @Override
    public Set<Transaction> getLockHolders(String lockId) {
        if (inner instanceof LockStateView lockStateView) {
            return lockStateView.getLockHolders(lockId);
        }
        return Collections.emptySet();
    }

    @Override
    public boolean holdsAnyLocks(Transaction txn) {
        return inner instanceof LockStateView lockStateView && lockStateView.holdsAnyLocks(txn);
    }

    private boolean isFirstLock(Transaction t, String res) {
        // Optional: track if t already acquired a lock on any resource
        return !txnTokenMap.containsKey(t);
    }
}
