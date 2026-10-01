package de.tum.tssimulator.execution;

import de.tum.tssimulator.locking.policy.DeadlockStrategy;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Manages adaptive concurrency control by monitoring system metrics and
 * dynamically switching deadlock strategies.
 * 
 * Logic:
 * 1. Monitor Lock Contention (alpha) and Abort Rate.
 * 2. If alpha > 0.226 (Thrashing) -> NO_WAIT
 * 3. If Abort Rate > 0.30 (High Conflict) -> WOUND_WAIT
 * 4. Else -> CAUTIOUS_WAITING
 */
public class AdaptiveConcurrencyManager {
    private static final AdaptiveConcurrencyManager INSTANCE = new AdaptiveConcurrencyManager();

    // Current Strategy
    private final AtomicReference<DeadlockStrategy> currentStrategy = new AtomicReference<>(
            DeadlockStrategy.CAUTIOUS_WAITING);

    // Metrics (Windowed)
    private final AtomicLong committedTxns = new AtomicLong(0);
    private final AtomicLong abortedTxns = new AtomicLong(0);
    private final AtomicLong currentLocksHeld = new AtomicLong(0);
    private final AtomicLong activeTxns = new AtomicLong(0);

    public void reset() {
        currentStrategy.set(DeadlockStrategy.CAUTIOUS_WAITING);
        committedTxns.set(0);
        abortedTxns.set(0);
        currentLocksHeld.set(0);
        activeTxns.set(0);
    }

    // Configuration
    private static final double ALPHA_THRESHOLD = 0.226;
    private static final double ABORT_RATE_THRESHOLD_HIGH = 0.30;
    private static final double ABORT_RATE_THRESHOLD_LOW = 0.05;

    // Total system locks (injected from SimulatorParameters)
    private int totalSystemLocks = 5000;

    private AdaptiveConcurrencyManager() {
    }

    public static AdaptiveConcurrencyManager getInstance() {
        return INSTANCE;
    }

    public void setTotalSystemLocks(int totalLocks) {
        this.totalSystemLocks = totalLocks;
    }

    // --- Metric Collection ---
    public void recordCommit() {
        committedTxns.incrementAndGet();
        activeTxns.decrementAndGet();
    }

    public void recordAbort() {
        abortedTxns.incrementAndGet();
        activeTxns.decrementAndGet();
    }

    public void recordTxnStart() {
        activeTxns.incrementAndGet();
    }

    public void recordLockAcquired() {
        currentLocksHeld.incrementAndGet();
    }

    public void recordLockReleased() {
        currentLocksHeld.decrementAndGet();
    }

    // --- Strategy Retrieval ---
    public DeadlockStrategy getCurrentStrategy() {
        return currentStrategy.get();
    }

    // --- Adaptation Loop (Called periodically) ---
    public void updateStrategy() {
        long committed = committedTxns.get();
        long aborted = abortedTxns.get();
        long totalOps = committed + aborted;

        // Avoid division by zero or reacting too fast
        if (totalOps < 10)
            return;

        double abortRate = (double) aborted / totalOps;

        // Alpha calculation: (Avg Active * Avg Locks/Txn) / Total Locks
        // Simplification: Total Locks Held / Total System Locks
        long locksHeld = currentLocksHeld.get();
        double alpha = (double) locksHeld / totalSystemLocks;

        DeadlockStrategy newStrategy;

        if (alpha > ALPHA_THRESHOLD) {
            // Case 1: Thrashing imminent. Flush queues.
            newStrategy = DeadlockStrategy.NO_WAIT;
        } else if (abortRate > ABORT_RATE_THRESHOLD_HIGH) {
            // Case 2: High conflict. Protect old transactions.
            newStrategy = DeadlockStrategy.WOUND_WAIT;
        } else {
            // Case 3: Normal load. Use cautious waiting to prevent deadlocks but allow
            // waiting.
            newStrategy = DeadlockStrategy.CAUTIOUS_WAITING;
        }

        if (currentStrategy.get() != newStrategy) {
            System.out.println("[AdaptiveCC] Switching to " + newStrategy + " (Alpha=" + String.format("%.3f", alpha)
                    + ", AbortRate=" + String.format("%.3f", abortRate) + ")");
            currentStrategy.set(newStrategy);
        }

        // Reset window counters logic could go here if we want sliding window
        // For simplicity, we just reset every epoch? Or keep accumulating?
        // Let's reset for "instantaneous" reaction.
        committedTxns.set(0);
        abortedTxns.set(0);
    }
}

