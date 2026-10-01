package de.tum.tssimulator.locking.core;

import java.util.Set;

import de.tum.tssimulator.core.Transaction;

/**
 * Read-only view of lock ownership state used by admission and diagnostics.
 */
public interface LockStateView {
    Set<Transaction> getLockHolders(String lockId);

    boolean holdsAnyLocks(Transaction txn);
}
