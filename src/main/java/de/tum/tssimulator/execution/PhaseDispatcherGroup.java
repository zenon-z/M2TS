package de.tum.tssimulator.execution;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import de.tum.tssimulator.core.TransactionGroup;

public class PhaseDispatcherGroup implements PhaseDispatcher {
    private final List<PhaseDispatcher> phases;

    public PhaseDispatcherGroup(List<PhaseDispatcher> phases) {
        this.phases = phases;
    }

    @Override
    public long dispatch(long timeBudget, long virtualTime) {
        // All phases run in parallel with full time budget (simulated parallelism)
        long maxUsed = 0;
        for (PhaseDispatcher phase : phases) {
            long phaseUsed = phase.dispatch(timeBudget, virtualTime);
            maxUsed = Math.max(maxUsed, phaseUsed);
        }
        return maxUsed;
    }

    @Override
    public int getInFlightCount() {
        return phases.stream().mapToInt(PhaseDispatcher::getInFlightCount).sum();
    }
    
    @Override
    public Set<TransactionGroup> getInFlightGroups() {
        return phases.stream()
                .flatMap(phase -> phase.getInFlightGroups().stream())
                .collect(Collectors.toSet());
    }
    
    @Override
    public int getNonBookkeeperCount() {
        return phases.stream().mapToInt(PhaseDispatcher::getNonBookkeeperCount).sum();
    }
    
    /**
     * Get a dispatcher of a specific type from the group (for debug instrumentation access)
     */
    public <T extends PhaseDispatcher> T getDispatcher(Class<T> type) {
        for (PhaseDispatcher phase : phases) {
            if (type.isInstance(phase)) {
                return type.cast(phase);
            }
        }
        return null;
    }
    
    /**
     * Clear all transactions belonging to a group from all processors.
     * Returns the total count of transactions cleared.
     */
    public int clearTransactionsByGroup(TransactionGroup group) {
        int totalCleared = 0;
        for (PhaseDispatcher phase : phases) {
            if (phase instanceof CompositePhaseDispatcher) {
                totalCleared += ((CompositePhaseDispatcher) phase).clearTransactionsByGroup(group);
            } else if (phase instanceof AbstractAdmissionController) {
                // Admission controller doesn't have processors, skip
            } else if (phase instanceof PhaseDispatcherGroup) {
                totalCleared += ((PhaseDispatcherGroup) phase).clearTransactionsByGroup(group);
            }
        }
        return totalCleared;
    }
}

