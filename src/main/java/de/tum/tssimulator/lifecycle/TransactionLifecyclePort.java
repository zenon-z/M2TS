package de.tum.tssimulator.lifecycle;

import de.tum.tssimulator.core.Transaction;
import de.tum.tssimulator.monitoring.SimulationLogger;

public interface TransactionLifecyclePort {
    void complete(Transaction txn);

    void abort(Transaction txn);

    void moveToAwaitingUserAction(Transaction txn);

    void moveToWaitingRoom(Transaction txn);

    SimulationLogger getLogger();
}
