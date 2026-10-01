package de.tum.tssimulator.execution;

import de.tum.tssimulator.lifecycle.TransactionLifecyclePort;
import de.tum.tssimulator.lifecycle.TransactionPoolManager;
import de.tum.tssimulator.monitoring.SimulationLogger;
import de.tum.tssimulator.state.DirtyStateTracker;

public class StepContext {
    public final PhaseDispatcher dispatcher;
    public final TransactionPoolManager poolManager;
    public final TransactionLifecyclePort lifecycle;
    public final DirtyStateTracker dirtySetManager;  // NEW: For BASE mode sampling
    public final SimulationLogger logger;  // NEW: For dirty set sampling
    public long virtualTime = 0;  // Current virtual time for exponential backoff

    public StepContext(PhaseDispatcher dispatcher, 
                      TransactionPoolManager poolManager,
                      TransactionLifecyclePort lifecycle,
                      DirtyStateTracker dirtySetManager,
                      SimulationLogger logger) {
        this.dispatcher = dispatcher;
        this.poolManager = poolManager;
        this.lifecycle = lifecycle;
        this.dirtySetManager = dirtySetManager;
        this.logger = logger;
    }
}

