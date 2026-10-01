package de.tum.tssimulator.scheduling;

import de.tum.tssimulator.core.Transaction;

public interface Scheduler {
    Transaction nextTransaction();
    boolean hasReady();
}
