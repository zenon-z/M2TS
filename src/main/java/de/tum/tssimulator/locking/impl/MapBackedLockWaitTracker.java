package de.tum.tssimulator.locking.impl;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import de.tum.tssimulator.core.Transaction;
import de.tum.tssimulator.locking.core.LockWaitTracker;

/**
 * Map-based implementation of lock wait tracking.
 * Maintains a map from lock IDs to sets of waiting transactions.
 */
public final class MapBackedLockWaitTracker implements LockWaitTracker {
    private final Map<String, Set<Transaction>> waiters = new LinkedHashMap<>();

    @Override
    public void markWaiting(Transaction t, String lockId) {
        waiters.computeIfAbsent(lockId, k -> new LinkedHashSet<>()).add(t);
    }

    @Override
    public void clearWaiting(Transaction t, String lockId) {
        Set<Transaction> s = waiters.get(lockId);
        if (s == null) return;
        s.remove(t);
        if (s.isEmpty()) {
            waiters.remove(lockId);
        }
    }

    @Override
    public boolean isWaiting(Transaction t) {
        for (Set<Transaction> s : waiters.values()) {
            if (s.contains(t)) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * Get waiters for a specific lock (for compatibility with existing code).
     */
    public Set<Transaction> getWaitersForLock(String lockId) {
        Set<Transaction> s = waiters.get(lockId);
        return (s != null) ? new LinkedHashSet<>(s) : new LinkedHashSet<>();
    }
    
    /**
     * Get all lock IDs that have waiters.
     */
    public Set<String> getLocksWithWaiters() {
        return new LinkedHashSet<>(waiters.keySet());
    }
    
    @Override
    public int totalWaitersCount() {
        int total = 0;
        for (Set<Transaction> s : waiters.values()) {
            total += s.size();
        }
        return total;
    }
    
    @Override
    public int locksWithWaitersCount() {
        return waiters.size();
    }
}
