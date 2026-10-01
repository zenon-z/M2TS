package test.java.core;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import de.tum.tssimulator.core.Transaction;
import de.tum.tssimulator.core.TransactionEvent;
import de.tum.tssimulator.core.TransactionState;
import de.tum.tssimulator.core.TransactionStateMachine;

import static org.junit.jupiter.api.Assertions.*;

public class TransactionStateMachineTest {

    @Test
    void invalidTransitionsThrow() {
        Transaction txn = new Transaction("T1", null, null);
        txn.initializeState();
        TransactionStateMachine fsm = new TransactionStateMachine(txn);
        // PHASE_STARTED requires RUNNING state
        Assertions.assertThrows(IllegalStateException.class, () -> fsm.fire(TransactionEvent.PHASE_STARTED));
        fsm.fire(TransactionEvent.CREATED);
        // Still in PENDING after CREATED
        Assertions.assertThrows(IllegalStateException.class, () -> fsm.fire(TransactionEvent.PHASE_STARTED));
        // Transition to RUNNING via LOCKS_ACQUIRED
        fsm.fire(TransactionEvent.LOCKS_ACQUIRED);
        fsm.fire(TransactionEvent.PHASE_STARTED);
        fsm.fire(TransactionEvent.PHASE_COMPLETED);
    }
    
    @Test
    void lockDeniedTransitionsToBlocked() {
        Transaction txn = new Transaction("T1", null, null);
        txn.initializeState();
        
        // Start: PENDING (after initializeState)
        assertEquals(TransactionState.PENDING, txn.getState());
        
        // Lock denied from PENDING: PENDING -> BLOCKED
        txn.transitionTo(TransactionEvent.LOCK_DENIED);
        assertEquals(TransactionState.BLOCKED, txn.getState());
    }
    
    @Test
    void lockAcquiredTransitionsFromBlockedToRunning() {
        Transaction txn = new Transaction("T1", null, null);
        txn.initializeState();
        TransactionStateMachine fsm = new TransactionStateMachine(txn);
        
        // Get to BLOCKED state (CREATED stays in PENDING, LOCK_DENIED from PENDING)
        fsm.fire(TransactionEvent.CREATED);
        fsm.fire(TransactionEvent.LOCK_DENIED);
        assertEquals(TransactionState.BLOCKED, fsm.getState());
        
        // Ready: BLOCKED -> READY -> RUNNING (two steps)
        fsm.fire(TransactionEvent.LOCKS_AVAILABLE);
        assertEquals(TransactionState.READY, fsm.getState());
        fsm.fire(TransactionEvent.CAPACITY_AVAILABLE);
        assertEquals(TransactionState.RUNNING, fsm.getState());
    }
    
    @Test
    void lockDeniedOnlyValidFromRunning() {
        Transaction txn = new Transaction("T1", null, null);
        txn.initializeState();
        
        // CAN deny lock from PENDING (lock acquisition failed)
        assertEquals(TransactionState.PENDING, txn.getState());
        txn.transitionTo(TransactionEvent.LOCK_DENIED);
        assertEquals(TransactionState.BLOCKED, txn.getState());
        
        // Cannot deny lock from BLOCKED
        assertThrows(IllegalStateException.class, () -> txn.transitionTo(TransactionEvent.LOCK_DENIED));
    }
    
    @Test
    void fullLockConflictCycle() {
        Transaction txn = new Transaction("T1", null, null);
        txn.initializeState();
        TransactionStateMachine fsm = new TransactionStateMachine(txn);
        
        // Create transaction (stays in PENDING)
        fsm.fire(TransactionEvent.CREATED);
        assertEquals(TransactionState.PENDING, fsm.getState());
        
        // Try to acquire lock, get denied from PENDING
        fsm.fire(TransactionEvent.LOCK_DENIED);
        assertEquals(TransactionState.BLOCKED, fsm.getState());
        
        // Lock becomes available, retry (BLOCKED → READY → RUNNING)
        fsm.fire(TransactionEvent.LOCKS_AVAILABLE);
        assertEquals(TransactionState.READY, fsm.getState());
        fsm.fire(TransactionEvent.CAPACITY_AVAILABLE);
        assertEquals(TransactionState.RUNNING, fsm.getState());
        
        // Can continue with phases
        fsm.fire(TransactionEvent.PHASE_STARTED);
        assertEquals(TransactionState.RUNNING, fsm.getState());
        
        fsm.fire(TransactionEvent.PHASE_COMPLETED);
        assertEquals(TransactionState.RUNNING, fsm.getState());
        
        // Complete successfully
        fsm.fire(TransactionEvent.COMPLETED);
        assertEquals(TransactionState.COMPLETED, fsm.getState());
    }
    
    @Test
    void multipleBlockAndRetryAttempts() {
        Transaction txn = new Transaction("T1", null, null);
        txn.initializeState();
        TransactionStateMachine fsm = new TransactionStateMachine(txn);
        
        fsm.fire(TransactionEvent.CREATED);
        
        // First attempt - blocked from PENDING
        fsm.fire(TransactionEvent.LOCK_DENIED);
        assertEquals(TransactionState.BLOCKED, fsm.getState());
        
        // First retry - BLOCKED → READY → RUNNING, then blocked again
        fsm.fire(TransactionEvent.LOCKS_AVAILABLE);
        assertEquals(TransactionState.READY, fsm.getState());
        fsm.fire(TransactionEvent.CAPACITY_AVAILABLE);
        assertEquals(TransactionState.RUNNING, fsm.getState());
        fsm.fire(TransactionEvent.LOCK_DENIED);
        assertEquals(TransactionState.BLOCKED, fsm.getState());
        
        // Second retry - still blocked
        fsm.fire(TransactionEvent.LOCKS_AVAILABLE);
        assertEquals(TransactionState.READY, fsm.getState());
        fsm.fire(TransactionEvent.CAPACITY_AVAILABLE);
        assertEquals(TransactionState.RUNNING, fsm.getState());
        fsm.fire(TransactionEvent.LOCK_DENIED);
        assertEquals(TransactionState.BLOCKED, fsm.getState());
        
        // Third retry - success
        fsm.fire(TransactionEvent.LOCKS_AVAILABLE);
        assertEquals(TransactionState.READY, fsm.getState());
        fsm.fire(TransactionEvent.CAPACITY_AVAILABLE);
        assertEquals(TransactionState.RUNNING, fsm.getState());
        
        // Can now proceed
        fsm.fire(TransactionEvent.COMPLETED);
        assertEquals(TransactionState.COMPLETED, fsm.getState());
    }
    
    @Test
    void abortFromBlockedState() {
        Transaction txn = new Transaction("T1", null, null);
        txn.initializeState();
        TransactionStateMachine fsm = new TransactionStateMachine(txn);
        
        // CREATED stays in PENDING, then LOCK_DENIED from PENDING
        fsm.fire(TransactionEvent.CREATED);
        fsm.fire(TransactionEvent.LOCK_DENIED);
        assertEquals(TransactionState.BLOCKED, fsm.getState());
        
        // Can abort from BLOCKED state
        fsm.fire(TransactionEvent.ABORTED);
        assertEquals(TransactionState.ABORTED, fsm.getState());
    }
}
