package de.tum.tssimulator.core;

public enum TransactionState {
    UNINITIALIZED,  // Initial state before any event is fired
    PENDING,
    RUNNING,
    BACKGROUND_WORK,  // BASE bookkeepers executing reconciliation work
    BLOCKED,  // Transaction blocked waiting for locks (phases preserved)
    READY,    // Transaction has locks and is ready to execute, waiting for execution slot
    AWAITING_USER_ACTION,  // Transaction set aside for manual intervention (ON_RESTRICT)
    COMPLETED,
    ABORTED
}
