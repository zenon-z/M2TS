package de.tum.tssimulator.core;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Global counter tracking how many transactions are in each state.
 * This provides O(1) lookup for transaction state counts.
 */
public class TransactionStateCounter {
    private static final Map<TransactionState, AtomicInteger> counters = new EnumMap<>(TransactionState.class);
    
    static {
        // Initialize counters for all states
        for (TransactionState state : TransactionState.values()) {
            counters.put(state, new AtomicInteger(0));
        }
    }
    
    /**
     * Increment counter when transaction enters a state.
     */
    public static void increment(TransactionState state) {
        counters.get(state).incrementAndGet();
    }
    
    /**
     * Decrement counter when transaction leaves a state.
     */
    public static void decrement(TransactionState state) {
        counters.get(state).decrementAndGet();
    }
    
    /**
     * Get current count of transactions in a specific state.
     */
    public static int getCount(TransactionState state) {
        return counters.get(state).get();
    }
    
    /**
     * Reset all counters (for testing).
     */
    public static void reset() {
        for (AtomicInteger counter : counters.values()) {
            counter.set(0);
        }
    }
    
    /**
     * Get count of non-bookkeeper transactions in RUNNING state.
     * This is used for processor capacity limiting.
     */
    public static int getRunningNonBookkeeperCount() {
        // For now, return total RUNNING count
        // We'll refine this to exclude bookkeepers
        return getCount(TransactionState.RUNNING);
    }
}
