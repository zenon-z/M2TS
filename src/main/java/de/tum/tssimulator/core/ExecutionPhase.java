package de.tum.tssimulator.core;

public enum ExecutionPhase {
    LOCK,           // Lock acquisition phase
    IO,             // I/O processing phase
    CPU,            // CPU processing phase
    ROLLBACK,       // Abort rollback phase
    RECONCILIATION, // Reconciliation phase (BASE bookkeepers only)
    COMPLETE        // Transaction complete
}

