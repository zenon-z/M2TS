package de.tum.tssimulator.execution;

import de.tum.tssimulator.bookkeeping.BookkeeperCreationCoordinator;
import de.tum.tssimulator.bookkeeping.BookkeeperQueueRouter;
import de.tum.tssimulator.config.SimulatorConfig;
import de.tum.tssimulator.execution.init.TransactionPhaseInitializer;
import de.tum.tssimulator.locking.coordinator.TransactionLockCoordinator;
import de.tum.tssimulator.monitoring.SimulationLogger;

/**
 * Factory for creating concurrency strategies based on configuration.
 */
public class ConcurrencyStrategyFactory {
    
    public static ConcurrencyStrategy createStrategy(
            SimulatorConfig config,
            TransactionLockCoordinator lockCoordinator,
            TransactionPhaseInitializer phaseInitializer,
            BookkeeperCreationCoordinator coordinator,
            BookkeeperQueueRouter router,
            SimulationLogger logger) {
        
        switch (config.getConcurrencyModel()) {
            case ACID:
                return new ACIDStrategy(
                    lockCoordinator,
                    phaseInitializer,
                    coordinator,
                    router
                );
                
            case BASE:
                // BASE: bookkeepers execute on dedicated reconciliation processors
                // No periodic reconciliation interval needed
                return new BASEStrategy(
                    phaseInitializer,
                    coordinator,
                    router,
                    logger
                );
                
            default:
                throw new IllegalArgumentException(
                    "Unknown concurrency model: " + config.getConcurrencyModel());
        }
    }
}
