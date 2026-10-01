package de.tum.tssimulator.locking.impl;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import de.tum.tssimulator.core.Transaction;
import de.tum.tssimulator.core.TransactionGroup;
import de.tum.tssimulator.execution.AdmissionWaitManager;
import de.tum.tssimulator.locking.core.AdmissionAwareLockManager;
import de.tum.tssimulator.locking.core.GlobalLockBudget;
import de.tum.tssimulator.locking.core.LockManager;
import de.tum.tssimulator.locking.core.LockStateView;
import de.tum.tssimulator.locking.core.LockType;
import de.tum.tssimulator.locking.policy.LockDecision;
import de.tum.tssimulator.locking.policy.SoftDenyDecision;
import de.tum.tssimulator.queue.QueueType;

/**
 * Decorator that enforces a global token budget around an underlying
 * LockManager.
 * Pure budgeting semantics: deny if insufficient tokens, deduct on grant,
 * refund on release.
 * 
 * CRITICAL: Group-aware budgeting! When bookkeepers share locks with parent,
 * only the first acquisition charges budget. Subsequent group members get it
 * free.
 */
public class BudgetedLockManagerDecorator implements LockManager, AdmissionAwareLockManager, LockStateView {
    private final LockManager delegate;
    private final GlobalLockBudget budget;
    private final Map<Transaction, Integer> tokensHeld = new HashMap<>();
    // Track which resources each group holds (for shared lock optimization)
    private final Map<TransactionGroup, Set<String>> groupResources = new HashMap<>();

    public BudgetedLockManagerDecorator(LockManager delegate, GlobalLockBudget budget) {
        this.delegate = delegate;
        this.budget = budget;
    }

    /**
     * Get the underlying delegate lock manager.
     * Used by SoftDenyDecision to access SS2PLLockManager-specific methods.
     */
    public LockManager getDelegate() {
        return delegate;
    }

    @Override
    public LockDecision requestLock(Transaction t, String resource, LockType type) {
        // CRITICAL FIX: Check if group already holds this resource (shared lock -
        // free!)
        TransactionGroup group = t.getGroup();
        boolean groupAlreadyHolds = false;
        if (group != null) {
            Set<String> resources = groupResources.get(group);
            if (resources != null && resources.contains(resource)) {
                groupAlreadyHolds = true;
            }
        }

        // If group already holds it, let delegate handle (will be granted free)
        if (groupAlreadyHolds) {
            return delegate.requestLock(t, resource, type);
        }

        // New lock acquisition - check budget first
        if (!budget.tryAcquire(1)) {
            return new SoftDenyDecision(QueueType.BLOCKED_TXN);
        }

        LockDecision decision = delegate.requestLock(t, resource, type);
        if (decision.granted()) {
            // Track tokens held by this transaction
            tokensHeld.merge(t, 1, Integer::sum);
            // Track resource held by this group
            if (group != null) {
                groupResources.computeIfAbsent(group, k -> new LinkedHashSet<>()).add(resource);
            }
            return decision;
        } else {
            // Refund if not granted
            budget.release(1);
            return decision;
        }
    }

    @Override
    public void releaseLocks(Transaction t) {
        delegate.releaseLocks(t);
        // Refund all tokens held by this transaction
        Integer tokens = tokensHeld.remove(t);
        if (tokens != null) {
            budget.release(tokens);
        }
        // Clean up group resources if this was the parent or last group member
        TransactionGroup group = t.getGroup();
        if (group != null) {
            // Remove group resource tracking when group is done
            // (parent releases at the end for whole group)
            if (t == group.getParent()) {
                groupResources.remove(group);
            }
        }
    }

    @Override
    public boolean causesDeadlock(Transaction t, String resource) {
        return delegate.causesDeadlock(t, resource);
    }

    @Override
    public void abortTransactionGroup(TransactionGroup group) {
        delegate.abortTransactionGroup(group);
    }

    @Override
    public boolean tryAcquireAllLocks(Transaction t, Map<String, LockType> requested) {
        // Check if enough budget tokens available for all locks
        if (!budget.tryAcquire(requested.size())) {
            return false; // Insufficient budget
        }

        // Try to acquire all locks atomically
        boolean success = delegate.tryAcquireAllLocks(t, requested);

        if (success) {
            // Track tokens held by this transaction
            tokensHeld.merge(t, requested.size(), Integer::sum);
            // Track resources held by this group
            TransactionGroup group = t.getGroup();
            if (group != null) {
                Set<String> resources = groupResources.computeIfAbsent(group, k -> new LinkedHashSet<>());
                resources.addAll(requested.keySet());
            }
        } else {
            // Refund if not acquired
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
        return delegate.checkLocksAvailable(t, requested);
    }

    /**
     * Set the admission wait manager on the delegate if supported.
     */
    @Override
    public void setAdmissionWaitManager(AdmissionWaitManager manager) {
        if (delegate instanceof AdmissionAwareLockManager admissionAwareLockManager) {
            admissionAwareLockManager.setAdmissionWaitManager(manager);
        }
    }

    /**
     * Register a transaction as waiting for a specific lock.
     * Delegates to underlying lock manager.
     */
    @Override
    public void registerWaiter(Transaction txn, String lockId) {
        delegate.registerWaiter(txn, lockId);
    }

    /**
     * Get all transactions waiting for locks held by a specific transaction.
     * Delegates to underlying lock manager.
     */
    @Override
    public Set<Transaction> getWaitersForTransaction(Transaction holder) {
        return delegate.getWaitersForTransaction(holder);
    }

    @Override
    public Set<Transaction> getLockHolders(String lockId) {
        if (delegate instanceof LockStateView lockStateView) {
            return lockStateView.getLockHolders(lockId);
        }
        return new HashSet<>();
    }

    @Override
    public boolean holdsAnyLocks(Transaction txn) {
        return delegate instanceof LockStateView lockStateView && lockStateView.holdsAnyLocks(txn);
    }
}
