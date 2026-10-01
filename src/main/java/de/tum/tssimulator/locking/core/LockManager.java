package de.tum.tssimulator.locking.core;

import java.util.Map;
import java.util.Set;

import de.tum.tssimulator.core.Transaction;
import de.tum.tssimulator.core.TransactionGroup;
import de.tum.tssimulator.locking.policy.LockDecision;

public interface LockManager {
	LockDecision requestLock(Transaction t, String resourceId, LockType type);
    void releaseLocks(Transaction t);
    void abortTransactionGroup(TransactionGroup group);
    boolean causesDeadlock(Transaction t, String blockedOn);
    
    /**
     * C2PL preclaim: Atomically check if all required locks are free and acquire them.
     * If any lock is unavailable, acquires none.
     *
     * @param t Transaction requesting locks
     * @param requested lock id -> mode required by the transaction. Iteration order is
     *        significant (acquisition order), so callers pass an order-stable map.
     * @return true if all locks acquired, false if any unavailable
     */
    boolean tryAcquireAllLocks(Transaction t, Map<String, LockType> requested);

    /**
     * Check if all required locks are available WITHOUT acquiring them.
     * Used for event-driven admission wait: check first, then register waiter if blocked.
     *
     * @param t Transaction requesting locks
     * @param requested lock id -> mode required by the transaction
     * @return null if all locks available, or first blocking lockId
     */
    String checkLocksAvailable(Transaction t, Map<String, LockType> requested);
    
    /**
     * Register a transaction as waiting for a specific lock, so it can be retried when that
     * lock is released.
     * 
     * @param txn The transaction waiting for the lock
     * @param lockId The lock being waited for
     */
    void registerWaiter(Transaction txn, String lockId);
    
    /**
     * Get all transactions waiting for locks held by a specific transaction.
     * Used for cascade abort deadlock prevention.
     * 
     * @param holder The transaction holding locks
     * @return Set of transactions waiting for holder's locks
     */
    Set<Transaction> getWaitersForTransaction(Transaction holder);
}
