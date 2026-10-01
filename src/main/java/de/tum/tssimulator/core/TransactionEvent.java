package de.tum.tssimulator.core;

public enum TransactionEvent {
    CREATED,           // Parent/ACID bookkeeper creation → PENDING
    BACKGROUND_CREATED,  // BASE bookkeeper creation → BACKGROUND_WORK
    LOCKS_ACQUIRED,  // All locks successfully acquired, transition from PENDING to RUNNING (ACID mode)
    READY_TO_EXECUTE,  // Ready to execute without locks (BASE mode) - transition from PENDING to RUNNING
    PHASE_STARTED,
    PHASE_COMPLETED,
    LOCKS_AVAILABLE,  // Blocking locks were released, transition from BLOCKED to READY
    CAPACITY_AVAILABLE,  // Execution slot available, transition from READY to RUNNING
    LOCK_DENIED,  // Lock acquisition failed, transition to BLOCKED
    ROLLBACK_QUEUED,  // Abort rollback is waiting for processor capacity
    AWAITING_USER_ACTION,  // Transaction set aside for manual intervention
    COMPLETED,
    ABORTED
}
