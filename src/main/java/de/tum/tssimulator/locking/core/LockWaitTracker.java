package de.tum.tssimulator.locking.core;

import de.tum.tssimulator.core.Transaction;

/**
 * Tracks which transactions are waiting for which locks.
 * Shared interface between lock manager (which updates state) and wait policy (which queries state).
 */
public interface LockWaitTracker {
    /**
     * Mark a transaction as waiting for a specific lock.
     */
    void markWaiting(Transaction t, String lockId);
    
    /**
     * Clear a transaction's wait on a specific lock (acquired or aborted).
     */
    void clearWaiting(Transaction t, String lockId);
    
    /**
     * Check if a transaction is waiting for any lock.
     */
    boolean isWaiting(Transaction t);
    
    /**
     * Get total count of all waiters across all locks.
     */
    int totalWaitersCount();
    
    /**
     * Get count of locks that have waiters.
     */
    int locksWithWaitersCount();
}
