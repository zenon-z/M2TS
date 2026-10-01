package de.tum.tssimulator.bootstrap;

import de.tum.tssimulator.queue.QueuePort;
import de.tum.tssimulator.scheduling.Scheduler;
import de.tum.tssimulator.scheduling.TransactionScheduler;

public interface SchedulingBundle {
    QueuePort queues();
    Scheduler scheduler();
    TransactionScheduler txnScheduler();
}
