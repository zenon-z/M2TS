package de.tum.tssimulator.bootstrap;

import de.tum.tssimulator.config.SimulatorConfig;
import de.tum.tssimulator.monitoring.SimulationLogger;
import de.tum.tssimulator.monitoring.SimulationMonitor;
import de.tum.tssimulator.state.DirtyStateTracker;
import de.tum.tssimulator.state.TransactionGroupRegistry;

public interface RuntimeServices {
    SimulatorConfig config();
    SimulationLogger logger();
    SimulationMonitor monitor();
    TransactionGroupRegistry registry();
    DirtyStateTracker dirtyState();
}
