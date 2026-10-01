package de.tum.tssimulator.locking.policy;

import de.tum.tssimulator.core.Transaction;
import de.tum.tssimulator.locking.core.LockManager;
import de.tum.tssimulator.monitoring.SimulationLogger;
import de.tum.tssimulator.queue.QueueManager;

public interface LockDecision {
    boolean granted();
    void handle(Transaction requester, String resource, LockManager lockManager, QueueManager queueManager, SimulationLogger logger);
}

