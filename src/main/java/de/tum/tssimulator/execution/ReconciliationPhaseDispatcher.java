package de.tum.tssimulator.execution;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import de.tum.tssimulator.core.TransactionGroup;
import de.tum.tssimulator.model.IOModel;
import de.tum.tssimulator.monitoring.SimulationLogger;
import de.tum.tssimulator.queue.QueuePort;
import de.tum.tssimulator.state.DirtyStateTracker;

/**
 * Phase dispatcher for BASE reconciliation work.
 * 
 * Handles bookkeeper execution on dedicated reconciliation processors, separate from parent processors.
 * Key responsibilities:
 * - Poll RECONCILIATION queue for bookkeeper transactions
 * - Remove dirty keys when bookkeeper starts execution (immediate cleanup)
 * - Execute bookkeepers through IO → CPU phases with normal computation cost
 * - Prevent parent transaction starvation via capacity separation
 */
public class ReconciliationPhaseDispatcher implements PhaseDispatcher {
    private final List<ReconciliationProcessorUnit> units;
    private final QueuePort queue;
    private final IOModel ioModel;
    private final DirtyStateTracker dirtySetManager;
    private final SimulationLogger logger;

    public ReconciliationPhaseDispatcher(List<ReconciliationProcessorUnit> units,
                                         QueuePort queue,
                                         IOModel ioModel,
                                         DirtyStateTracker dirtySetManager,
                                         SimulationLogger logger) {
        this.units = units;
        this.queue = queue;
        this.ioModel = ioModel;
        this.dirtySetManager = dirtySetManager;
        this.logger = logger;
    }

    @Override
    public long dispatch(long timeBudget, long virtualTime) {
        // All reconciliation processors execute in parallel, each getting the full timeBudget
        long maxUsed = 0;
        for (ReconciliationProcessorUnit unit : units) {
            // Each processor gets the FULL time budget (they run in parallel in simulated time)
            long stepUsed = unit.step(timeBudget, queue, ioModel, dirtySetManager, logger, virtualTime);
            // Track the maximum time used by any processor (this is how much simulated time advances)
            maxUsed = Math.max(maxUsed, stepUsed);
        }
        // Return the maximum time used (simulated time advances by the slowest processor)
        return maxUsed;
    }

    @Override
    public int getInFlightCount() {
        return units.stream().mapToInt(ReconciliationProcessorUnit::getInFlightCount).sum();
    }
    
    @Override
    public Set<TransactionGroup> getInFlightGroups() {
        return units.stream()
                .map(ReconciliationProcessorUnit::getInFlightGroup)
                .filter(group -> group != null)
                .collect(Collectors.toSet());
    }
    
    @Override
    public int getNonBookkeeperCount() {
        // Reconciliation processors only execute bookkeepers, so this is always 0
        return 0;
    }
}
