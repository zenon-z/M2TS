package de.tum.tssimulator.core;

public class TransactionStateMachine {
    private TransactionState current = TransactionState.UNINITIALIZED; // NEW: Start uninitialized
    private final Transaction transaction;

    public TransactionStateMachine(Transaction transaction) {
        this.transaction = transaction;
        // Don't increment counter yet - will happen on first transition
    }

    public TransactionState getState() {
        return current;
    }

    public void reset() {
        // Reset to RUNNING state (transaction has already been created)
        // This allows retried transactions to immediately attempt lock acquisition
        transitionTo(TransactionState.RUNNING);
    }

    private void transitionTo(TransactionState newState) {
        if (current != newState) {
            // Use polymorphic method instead of instanceof
            if (transaction.countsTowardLimit()) {
                // CRITICAL: Only decrement if we were in a real state (not UNINITIALIZED)
                // First transition from UNINITIALIZED just increments, no decrement
                if (current != TransactionState.UNINITIALIZED) {
                    TransactionStateCounter.decrement(current);
                }
                // Always increment new state
                TransactionStateCounter.increment(newState);
            }
            VictimTraceLog.log("STATE", transaction.getId(), "state " + current + " -> " + newState);
            current = newState;
        }
    }

    public void fire(TransactionEvent event) {
        VictimTraceLog.log("EVENT", transaction.getId(), "event=" + event + " currentState=" + current);
        switch (event) {
            case CREATED:
                if (current != TransactionState.UNINITIALIZED)
                    throw new IllegalStateException("CREATED only from UNINITIALIZED");
                transitionTo(TransactionState.PENDING);
                break;
            case BACKGROUND_CREATED:
                if (current != TransactionState.UNINITIALIZED)
                    throw new IllegalStateException("BACKGROUND_CREATED only from UNINITIALIZED");
                transitionTo(TransactionState.BACKGROUND_WORK);
                break;
            case LOCKS_ACQUIRED:
                if (current != TransactionState.PENDING)
                    throw new IllegalStateException("LOCKS_ACQUIRED only from PENDING");
                transitionTo(TransactionState.RUNNING);
                break;
            case READY_TO_EXECUTE:
                if (current != TransactionState.PENDING)
                    throw new IllegalStateException("READY_TO_EXECUTE only from PENDING");
                transitionTo(TransactionState.RUNNING);
                break;
            case LOCKS_AVAILABLE:
                if (current != TransactionState.BLOCKED)
                    throw new IllegalStateException("LOCKS_AVAILABLE only from BLOCKED, not: " + current);
                // BLOCKED → READY (got locks freed, waiting for execution slot)
                transitionTo(TransactionState.READY);
                break;
            case CAPACITY_AVAILABLE:
                if (current == TransactionState.ABORTED) {
                    // Start of race condition handling:
                    // If capacity becomes available but we were already aborted,
                    // just ignore it. The transaction is dead.
                    return;
                }
                if (current != TransactionState.READY)
                    throw new IllegalStateException("CAPACITY_AVAILABLE only from READY, not: " + current);
                // READY → RUNNING (got execution slot)
                transitionTo(TransactionState.RUNNING);
                break;
            case LOCK_DENIED:
                // Can occur from PENDING (lock acquisition failed) or RUNNING (group member
                // blocked)
                if (current != TransactionState.PENDING && current != TransactionState.RUNNING)
                    throw new IllegalStateException("LOCK_DENIED from invalid state: " + current);
                transitionTo(TransactionState.BLOCKED);
                break;
            case ROLLBACK_QUEUED:
                if (current == TransactionState.COMPLETED || current == TransactionState.ABORTED)
                    throw new IllegalStateException("ROLLBACK_QUEUED from terminal state: " + current);
                transitionTo(TransactionState.READY);
                break;
            case PHASE_STARTED:
                if (current != TransactionState.RUNNING && current != TransactionState.BACKGROUND_WORK)
                    throw new IllegalStateException("PHASE_STARTED from invalid state: " + current);
                // Stay in current state
                break;
            case PHASE_COMPLETED:
                if (current != TransactionState.RUNNING && current != TransactionState.BACKGROUND_WORK)
                    throw new IllegalStateException("PHASE_COMPLETED from invalid state: " + current);
                // remain in current state (no transition)
                break;
            case AWAITING_USER_ACTION:
                if (current != TransactionState.RUNNING)
                    throw new IllegalStateException("AWAITING_USER_ACTION from invalid state: " + current);
                transitionTo(TransactionState.AWAITING_USER_ACTION);
                break;
            case COMPLETED:
                if (current == TransactionState.COMPLETED)
                    return;
                if (current != TransactionState.RUNNING && current != TransactionState.BACKGROUND_WORK)
                    throw new IllegalStateException("COMPLETED from invalid state: " + current);
                transitionTo(TransactionState.COMPLETED);
                break;
            case ABORTED:
                if (current == TransactionState.ABORTED)
                    return;
                transitionTo(TransactionState.ABORTED);
                transaction.onAbort();
                break;
        }
    }
}
