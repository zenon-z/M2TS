package de.tum.tssimulator.scheduling;

import de.tum.tssimulator.core.Transaction;
import de.tum.tssimulator.queue.QueuePort;
import de.tum.tssimulator.queue.QueueType;

public class FIFOScheduler implements Scheduler {
    private final QueuePort queue;

    public FIFOScheduler(QueuePort queue) {
        this.queue = queue;
    }

    @Override
    public Transaction nextTransaction() {
        return queue.dequeue(QueueType.PENDING);
    }

    @Override
    public boolean hasReady() {
        return !queue.isEmpty(QueueType.PENDING);
    }
}
