package de.tum.tssimulator.bootstrap;

import de.tum.tssimulator.config.SimulatorConfig;
import de.tum.tssimulator.core.TransactionFactory;
import de.tum.tssimulator.model.IOModel;
import de.tum.tssimulator.queue.CentralQueueManager;
import de.tum.tssimulator.queue.QueuePort;
import de.tum.tssimulator.scheduling.FIFOScheduler;
import de.tum.tssimulator.scheduling.Scheduler;
import de.tum.tssimulator.scheduling.TransactionScheduler;
import de.tum.tssimulator.state.TransactionGroupRegistry;

public class DefaultSchedulingBundle implements SchedulingBundle {
    private final QueuePort queues;
    private final Scheduler scheduler;
    private final TransactionScheduler txnScheduler;

    public DefaultSchedulingBundle(
        SimulatorConfig cfg, 
        TransactionFactory factory, 
        TransactionGroupRegistry registry,
        IOModel ioModel
    ) {
        if (ioModel == null) {
            throw new IllegalArgumentException("IOModel is required for QueueManager");
        }
        
        CentralQueueManager qm = new CentralQueueManager(ioModel);
        qm.setBackoffScale(cfg.backoffScale);
        this.queues = qm;
        this.scheduler = new FIFOScheduler(qm);
        this.txnScheduler = new TransactionScheduler(cfg, qm, factory, registry);
    }

    @Override public QueuePort queues() { return queues; }
    @Override public Scheduler scheduler() { return scheduler; }
    @Override public TransactionScheduler txnScheduler() { return txnScheduler; }
}
