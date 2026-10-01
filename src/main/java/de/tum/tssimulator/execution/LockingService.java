package de.tum.tssimulator.execution;

import de.tum.tssimulator.core.Transaction;
import de.tum.tssimulator.execution.ConcurrencyStrategy;
import de.tum.tssimulator.locking.coordinator.TransactionLockCoordinator;

/**
 * Manages transaction locking with mode-specific strategies.
 * 
 * Responsibilities:
 * - Acquire locks for transactions using strategy
 * - Release locks for transactions using strategy
 * - Periodic maintenance (deadlock detection, timeout checks)
 * 
 * This service encapsulates locking logic and provides a cleaner
 * abstraction than passing TransactionProcessor everywhere.
 */
public class LockingService {
    private final TransactionLockCoordinator lockCoordinator;
    private final ConcurrencyStrategy strategy;

    public LockingService(TransactionLockCoordinator lockCoordinator,
                         ConcurrencyStrategy strategy) {
        this.lockCoordinator = lockCoordinator;
        this.strategy = strategy;
    }

    /**
     * Acquire locks for transaction using strategy.
     * 
     * @param txn Transaction to acquire locks for
     * @return true if locks acquired successfully, false if blocked
     */
    public boolean process(Transaction txn) {
        return strategy.process(txn);
    }

    /**
     * Release locks for transaction using strategy.
     * 
     * @param txn Transaction to release locks for
     */
    public void release(Transaction txn) {
        strategy.release(txn);
    }
    
    /**
     * Periodic maintenance (deadlock detection, timeout checks).
     * 
     * @param virtualTime Current virtual time in simulation
     */
    public void periodicMaintenance(long virtualTime) {
        strategy.periodicMaintenance(virtualTime);
    }
    
    /**
     * Get the underlying lock coordinator.
     * 
     * @return TransactionLockCoordinator instance
     */
    public TransactionLockCoordinator getLockCoordinator() {
        return lockCoordinator;
    }
    
    /**
     * Check if running in BASE mode.
     * 
     * @return true if BASE mode, false if ACID mode
     */
    public boolean isBaseMode() {
        return strategy != null && "BASE".equals(strategy.getName());
    }
}
