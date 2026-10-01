package de.tum.tssimulator.locking.policy;

import de.tum.tssimulator.core.Transaction;
import de.tum.tssimulator.locking.core.LockManager;
import de.tum.tssimulator.monitoring.SimulationLogger;
import de.tum.tssimulator.queue.QueueManager;

public class GrantedDecision implements LockDecision {
    @Override
    public boolean granted() {
        return true;
    }

    @Override
    public void handle(Transaction requester, String resource, LockManager lockManager,
                       QueueManager queueManager, SimulationLogger logger) {
        // No-op
    }
}
