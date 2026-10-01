package de.tum.tssimulator.lifecycle;

import de.tum.tssimulator.monitoring.SimulationLogger;
import de.tum.tssimulator.scheduling.TransactionScheduler;

public class TransactionPoolManager {
    private final TransactionScheduler scheduler;
    private final SimulationLogger logger;
    private final int targetPoolSize;

    public TransactionPoolManager(TransactionScheduler scheduler,
                                  SimulationLogger logger,
                                  int targetPoolSize) {
        this.scheduler = scheduler;
        this.logger = logger;
        this.targetPoolSize = targetPoolSize;
    }

    public void maintain() {
        int currentLive = currentOpenGroups();
        scheduler.maintainPoolSize(targetPoolSize, currentLive);
    }

    /**
     * The PRODUCER population: parents started but not yet committed.
     *
     * <p>Keyed on parent COMMITS, not group completions. Under ACID the two coincide - a parent
     * completes only when its group does - so this is unchanged there. Under BASE a parent commits
     * immediately and its bookkeepers drain afterwards, so keying on group completion would let
     * pending drains occupy the MPL and throttle the producer by the drain. That is the opposite of
     * BASE, and it would make lambda_offered depend on the reconciler count by construction -
     * reading as V3's criterion 2 failing when it is this method.
     */
    private int currentOpenGroups() {
        var metrics = logger.metrics();
        int closed = metrics.getParentsCommittedTotal() + metrics.getGroupsAbortedTotal();
        return Math.max(0, metrics.getGroupsStartedTotal() - closed);
    }
}
