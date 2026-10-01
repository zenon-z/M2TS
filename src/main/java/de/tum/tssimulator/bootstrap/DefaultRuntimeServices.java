package de.tum.tssimulator.bootstrap;

import de.tum.tssimulator.config.SimulatorConfig;
import de.tum.tssimulator.monitoring.SimulationLogger;
import de.tum.tssimulator.monitoring.SimulationMonitor;
import de.tum.tssimulator.state.DirtyStateTracker;
import de.tum.tssimulator.state.TransactionGroupRegistry;

public class DefaultRuntimeServices implements RuntimeServices {
    private final SimulatorConfig config;
    private final SimulationLogger logger;
    private final SimulationMonitor monitor;
    private final TransactionGroupRegistry registry;
    private final DirtyStateTracker dirtyState;

    public DefaultRuntimeServices(
            SimulatorConfig config,
            SimulationLogger logger,
            SimulationMonitor monitor,
            TransactionGroupRegistry registry,
            DirtyStateTracker dirtyState) {
        this.config = config;
        this.logger = logger;
        this.monitor = monitor;
        this.registry = registry;
        this.dirtyState = dirtyState;
    }

    @Override
    public SimulatorConfig config() {
        return config;
    }

    @Override
    public SimulationLogger logger() {
        return logger;
    }

    @Override
    public SimulationMonitor monitor() {
        return monitor;
    }

    @Override
    public TransactionGroupRegistry registry() {
        return registry;
    }

    @Override
    public DirtyStateTracker dirtyState() {
        return dirtyState;
    }
}
