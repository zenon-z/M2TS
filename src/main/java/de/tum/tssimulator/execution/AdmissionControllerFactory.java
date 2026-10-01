package de.tum.tssimulator.execution;

import de.tum.tssimulator.config.ConcurrencyModel;
import de.tum.tssimulator.config.SimulatorConfig;
import de.tum.tssimulator.model.IOModel;
import de.tum.tssimulator.monitoring.SimulationLogger;
import de.tum.tssimulator.queue.QueueManager;
import de.tum.tssimulator.scheduling.Scheduler;

/**
 * Factory for creating the appropriate admission controller based on
 * concurrency model.
 * 
 * ACID mode: Creates ACIDAdmissionController with C2PL preclaim logic
 * BASE mode: Creates BASEAdmissionController with optimistic admission
 */
public class AdmissionControllerFactory {

    /**
     * Create an admission controller based on the concurrency model in the config.
     * 
     * @param config               Simulator configuration containing concurrency
     *                             model
     * @param scheduler            Transaction scheduler
     * @param processor            Transaction processor with strategy
     * @param ioModel              I/O model for lock timing
     * @param logger               Simulation logger
     * @param downstreamPhases     Downstream phase dispatchers
     * @param maxConcurrent        Maximum concurrent transactions allowed
     * @param queueManager         Queue manager for transaction queues
     * @param admissionWaitManager Event-driven admission wait manager (ACID only,
     *                             can be null for BASE)
     * @return Appropriate admission controller subclass
     */
    public static AbstractAdmissionController create(
            SimulatorConfig config,
            Scheduler scheduler,
            TransactionProcessor processor,
            IOModel ioModel,
            SimulationLogger logger,
            PhaseDispatcher downstreamPhases,
            int maxConcurrent,
            QueueManager queueManager,
            AdmissionWaitManager admissionWaitManager) {

        if (config.concurrencyModel == ConcurrencyModel.ACID) {
            return new ACIDAdmissionController(
                    scheduler,
                    processor,
                    ioModel,
                    logger,
                    downstreamPhases,
                    maxConcurrent,
                    queueManager,
                    admissionWaitManager,
                    config.deadlockStrategy);
        } else {
            // BASE mode
            return new BASEAdmissionController(
                    scheduler,
                    processor,
                    ioModel,
                    logger,
                    downstreamPhases,
                    maxConcurrent,
                    queueManager);
        }
    }
}
