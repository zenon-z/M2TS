package de.tum.tssimulator.execution;

import de.tum.tssimulator.core.TransactionGroup;
import java.util.Set;

public interface PhaseDispatcher {
    long dispatch(long timeBudget, long virtualTime);
    
    /**
     * Returns the number of transactions currently being processed by this dispatcher
     * (i.e., not in any queue but actively being worked on by processor units).
     * Default implementation returns 0 for dispatchers that don't track this.
     */
    default int getInFlightCount() {
        return 0;
    }
    
    /**
     * Returns the set of unique TransactionGroups currently being processed by this dispatcher.
     * This is used for counting concurrent execution by group (the atomic unit of execution).
     * Default implementation returns empty set.
     */
    default Set<TransactionGroup> getInFlightGroups() {
        return Set.of();
    }
    
    /**
     * Returns count of non-bookkeeper transactions being processed.
     * Bookkeepers execute as part of their parent and don't consume processor slots.
     * Default implementation returns 0.
     */
    default int getNonBookkeeperCount() {
        return 0;
    }
}
