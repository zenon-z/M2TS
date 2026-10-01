package de.tum.tssimulator.execution.init;

import de.tum.tssimulator.core.Transaction;
import de.tum.tssimulator.model.IOModel;
import de.tum.tssimulator.queue.QueueManager;
import de.tum.tssimulator.queue.QueueType;

public class TransactionPhaseInitializer {
    private final QueueManager queueManager;
    private final IOModel ioModel;

    public TransactionPhaseInitializer(QueueManager queueManager, IOModel ioModel) {
        this.queueManager = queueManager;
        this.ioModel = ioModel;
    }

    public void initialize(Transaction txn) {
        // Initialize execution phases - transaction is now RUNNING (transitioned in acquireAllLocks)
        txn.resetPhase(ioModel); // phase = LOCK, remainingTime = lock time
        queueManager.enqueue(txn, QueueType.LOCK);
    }
    
    public QueueManager getQueueManager() {
        return queueManager;
    }
    
    public IOModel getIOModel() {
        return ioModel;
    }
}
