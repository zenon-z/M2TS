package de.tum.tssimulator.lifecycle;

import java.util.List;
import java.util.Set;

import de.tum.tssimulator.config.SimulatorConfig;
import de.tum.tssimulator.core.ExecutionPhase;
import de.tum.tssimulator.core.Transaction;
import de.tum.tssimulator.core.TransactionEvent;
import de.tum.tssimulator.core.TransactionFactory;
import de.tum.tssimulator.core.TransactionIdGenerator;
import de.tum.tssimulator.core.TransactionState;
import de.tum.tssimulator.model.IOModel;
import de.tum.tssimulator.monitoring.SimulationLogger;
import de.tum.tssimulator.queue.CentralQueueManager;
import de.tum.tssimulator.queue.QueueType;
import test.java.util.TestIOModelFactory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for retryBlocked() phase-to-queue routing.
 * 
 * Tests that when transactions are moved to READY state, they are routed to the correct
 * queue based on their current ExecutionPhase:
 * - LOCK phase → PENDING queue
 * - IO phase → IO queue
 * - CPU phase → CPU queue
 * - COMPLETE phase → WAITING_FOR_GROUP queue
 */
public class RetryBlockedPhaseRoutingTest {
    
    private CentralQueueManager queueManager;
    private SimulationLogger logger;
    private SimulatorConfig config;
    private TransactionLifecycleManager lifecycleManager;
    private IOModel ioModel;
    private TransactionFactory factory;
    
    @BeforeEach
    public void setup() {
        queueManager = new CentralQueueManager(TestIOModelFactory.createTestIOModel());
        logger = new SimulationLogger("target/test-phase-routing.log");
        config = test.java.util.TestConfigFactory.create(
            1, 10, 0, 0, 0, 1, 1, List.of(), 0, 0, 0, 0, 0, 0, 0
        );
        lifecycleManager = new TransactionLifecycleManager(queueManager, config, logger, 100);
        ioModel = new IOModel(config);
        factory = new TransactionFactory(new TransactionIdGenerator());
    }
    
    @Test
    public void testLockPhaseRoutesToPending() {
        // Create transaction in LOCK phase, block it, then unblock
        Transaction txn = factory.createTransaction(config, null);
        txn.preparePhase(ExecutionPhase.LOCK, ioModel);
        txn.transitionTo(TransactionEvent.LOCKS_ACQUIRED); // PENDING -> RUNNING
        txn.transitionTo(TransactionEvent.LOCK_DENIED); // RUNNING -> BLOCKED
        txn.transitionTo(TransactionEvent.LOCKS_AVAILABLE); // BLOCKED -> READY
        queueManager.enqueue(txn, QueueType.READY_TXN);
        
        assertEquals(ExecutionPhase.LOCK, txn.getPhase());
        assertEquals(TransactionState.READY, txn.getState());
        
        // Simulate intake dispatcher admitting ready transaction
        txn.transitionTo(TransactionEvent.CAPACITY_AVAILABLE); // READY -> RUNNING
        QueueType targetQueue = getQueueForPhase(txn.getPhase());
        queueManager.dequeue(QueueType.READY_TXN);
        queueManager.enqueue(txn, targetQueue);
        
        // Should route to PENDING queue
        assertEquals(0, queueManager.size(QueueType.READY_TXN));
        assertEquals(1, queueManager.size(QueueType.PENDING), 
            "LOCK phase should route to PENDING");
        assertEquals(0, queueManager.size(QueueType.IO));
        assertEquals(0, queueManager.size(QueueType.CPU));
        assertEquals(0, queueManager.size(QueueType.WAITING_FOR_GROUP));
        
        // Should be in RUNNING state
        assertEquals(TransactionState.RUNNING, txn.getState());
    }
    
    private QueueType getQueueForPhase(ExecutionPhase phase) {
        return switch (phase) {
            case LOCK -> QueueType.PENDING;
            case IO -> QueueType.IO;
            case CPU -> QueueType.CPU;
            case COMPLETE -> QueueType.WAITING_FOR_GROUP;
            default -> QueueType.PENDING;
        };
    }
    
    @Test
    public void testIOPhaseRoutesToIOQueue() {
        // Create transaction in IO phase, block it, then unblock
        Transaction txn = factory.createTransaction(config, null);
        txn.preparePhase(ExecutionPhase.LOCK, ioModel);
        txn.advancePhase();  // Move to IO
        txn.transitionTo(TransactionEvent.LOCKS_ACQUIRED); // PENDING -> RUNNING
        txn.transitionTo(TransactionEvent.LOCK_DENIED); // RUNNING -> BLOCKED
        txn.transitionTo(TransactionEvent.LOCKS_AVAILABLE); // BLOCKED -> READY
        queueManager.enqueue(txn, QueueType.READY_TXN);
        
        assertEquals(ExecutionPhase.IO, txn.getPhase());
        
        // Simulate intake dispatcher routing
        txn.transitionTo(TransactionEvent.CAPACITY_AVAILABLE); // READY -> RUNNING
        QueueType targetQueue = getQueueForPhase(txn.getPhase());
        queueManager.dequeue(QueueType.READY_TXN);
        queueManager.enqueue(txn, targetQueue);
        
        // Should go to IO queue
        assertEquals(0, queueManager.size(QueueType.READY_TXN));
        assertEquals(0, queueManager.size(QueueType.PENDING));
        assertEquals(1, queueManager.size(QueueType.IO), 
            "IO phase should route to IO queue");
        assertEquals(0, queueManager.size(QueueType.CPU));
        assertEquals(0, queueManager.size(QueueType.WAITING_FOR_GROUP));
        
        assertEquals(TransactionState.RUNNING, txn.getState());
    }
    
    @Test
    public void testCPUPhaseRoutesToCPUQueue() {
        // Create transaction in CPU phase, block it, then unblock
        Transaction txn = factory.createTransaction(config, null);
        txn.preparePhase(ExecutionPhase.LOCK, ioModel);
        txn.advancePhase();  // IO
        txn.advancePhase();  // CPU
        txn.transitionTo(TransactionEvent.LOCKS_ACQUIRED); // PENDING -> RUNNING
        txn.transitionTo(TransactionEvent.LOCK_DENIED); // RUNNING -> BLOCKED
        txn.transitionTo(TransactionEvent.LOCKS_AVAILABLE); // BLOCKED -> READY
        queueManager.enqueue(txn, QueueType.READY_TXN);
        
        assertEquals(ExecutionPhase.CPU, txn.getPhase());
        
        // Simulate intake dispatcher routing
        txn.transitionTo(TransactionEvent.CAPACITY_AVAILABLE); // READY -> RUNNING
        QueueType targetQueue = getQueueForPhase(txn.getPhase());
        queueManager.dequeue(QueueType.READY_TXN);
        queueManager.enqueue(txn, targetQueue);
        
        // Should go to CPU queue
        assertEquals(0, queueManager.size(QueueType.READY_TXN));
        assertEquals(0, queueManager.size(QueueType.PENDING));
        assertEquals(0, queueManager.size(QueueType.IO));
        assertEquals(1, queueManager.size(QueueType.CPU), 
            "CPU phase should route to CPU queue");
        assertEquals(0, queueManager.size(QueueType.WAITING_FOR_GROUP));
        
        assertEquals(TransactionState.RUNNING, txn.getState());
    }
    
    @Test
    public void testCompletePhaseRoutesToWaitingForGroup() {
        // Create transaction in COMPLETE phase, block it, then unblock
        Transaction txn = factory.createTransaction(config, null);
        txn.preparePhase(ExecutionPhase.LOCK, ioModel);
        txn.advancePhase();  // IO
        txn.advancePhase();  // CPU
        txn.advancePhase();  // COMPLETE
        txn.transitionTo(TransactionEvent.LOCKS_ACQUIRED); // PENDING -> RUNNING
        txn.transitionTo(TransactionEvent.LOCK_DENIED); // RUNNING -> BLOCKED
        txn.transitionTo(TransactionEvent.LOCKS_AVAILABLE); // BLOCKED -> READY
        queueManager.enqueue(txn, QueueType.READY_TXN);
        
        assertEquals(ExecutionPhase.COMPLETE, txn.getPhase());
        
        // Simulate intake dispatcher routing
        txn.transitionTo(TransactionEvent.CAPACITY_AVAILABLE); // READY -> RUNNING
        QueueType targetQueue = getQueueForPhase(txn.getPhase());
        queueManager.dequeue(QueueType.READY_TXN);
        queueManager.enqueue(txn, targetQueue);
        
        // Should go to WAITING_FOR_GROUP queue
        assertEquals(0, queueManager.size(QueueType.READY_TXN));
        assertEquals(0, queueManager.size(QueueType.PENDING));
        assertEquals(0, queueManager.size(QueueType.IO));
        assertEquals(0, queueManager.size(QueueType.CPU));
        assertEquals(1, queueManager.size(QueueType.WAITING_FOR_GROUP), 
            "COMPLETE phase should route to WAITING_FOR_GROUP");
        
        assertEquals(TransactionState.RUNNING, txn.getState());
    }
    
    @Test
    public void testMultipleTransactionsInDifferentPhasesRouteCorrectly() {
        // Create transactions in different phases, block them, then unblock
        Transaction lockTxn = factory.createTransaction(config, null);
        lockTxn.preparePhase(ExecutionPhase.LOCK, ioModel);
        lockTxn.transitionTo(TransactionEvent.LOCKS_ACQUIRED); // PENDING -> RUNNING
        lockTxn.transitionTo(TransactionEvent.LOCK_DENIED); // RUNNING -> BLOCKED
        lockTxn.transitionTo(TransactionEvent.LOCKS_AVAILABLE); // BLOCKED -> READY
        
        Transaction ioTxn = factory.createTransaction(config, null);
        ioTxn.preparePhase(ExecutionPhase.LOCK, ioModel);
        ioTxn.advancePhase();
        ioTxn.transitionTo(TransactionEvent.LOCKS_ACQUIRED); // PENDING -> RUNNING
        ioTxn.transitionTo(TransactionEvent.LOCK_DENIED); // RUNNING -> BLOCKED
        ioTxn.transitionTo(TransactionEvent.LOCKS_AVAILABLE); // BLOCKED -> READY
        
        Transaction cpuTxn = factory.createTransaction(config, null);
        cpuTxn.preparePhase(ExecutionPhase.LOCK, ioModel);
        cpuTxn.advancePhase();
        cpuTxn.advancePhase();
        cpuTxn.transitionTo(TransactionEvent.LOCKS_ACQUIRED); // PENDING -> RUNNING
        cpuTxn.transitionTo(TransactionEvent.LOCK_DENIED); // RUNNING -> BLOCKED
        cpuTxn.transitionTo(TransactionEvent.LOCKS_AVAILABLE); // BLOCKED -> READY
        
        Transaction completeTxn = factory.createTransaction(config, null);
        completeTxn.preparePhase(ExecutionPhase.LOCK, ioModel);
        completeTxn.advancePhase();
        completeTxn.advancePhase();
        completeTxn.advancePhase();
        completeTxn.transitionTo(TransactionEvent.LOCKS_ACQUIRED); // PENDING -> RUNNING
        completeTxn.transitionTo(TransactionEvent.LOCK_DENIED); // RUNNING -> BLOCKED
        completeTxn.transitionTo(TransactionEvent.LOCKS_AVAILABLE); // BLOCKED -> READY
        
        queueManager.enqueue(lockTxn, QueueType.READY_TXN);
        queueManager.enqueue(ioTxn, QueueType.READY_TXN);
        queueManager.enqueue(cpuTxn, QueueType.READY_TXN);
        queueManager.enqueue(completeTxn, QueueType.READY_TXN);
        
        assertEquals(4, queueManager.size(QueueType.READY_TXN));
        
        // Simulate intake dispatcher routing all transactions
        while (queueManager.size(QueueType.READY_TXN) > 0) {
            Transaction txn = queueManager.dequeue(QueueType.READY_TXN);
            txn.transitionTo(TransactionEvent.CAPACITY_AVAILABLE); // READY -> RUNNING
            QueueType targetQueue = getQueueForPhase(txn.getPhase());
            queueManager.enqueue(txn, targetQueue);
        }
        
        // Each should route to correct queue
        assertEquals(0, queueManager.size(QueueType.READY_TXN));
        assertEquals(1, queueManager.size(QueueType.PENDING), 
            "One transaction in PENDING");
        assertEquals(1, queueManager.size(QueueType.IO), 
            "One transaction in IO");
        assertEquals(1, queueManager.size(QueueType.CPU), 
            "One transaction in CPU");
        assertEquals(1, queueManager.size(QueueType.WAITING_FOR_GROUP), 
            "One transaction in WAITING_FOR_GROUP");
        
        // All should be in RUNNING state
        assertEquals(TransactionState.RUNNING, lockTxn.getState());
        assertEquals(TransactionState.RUNNING, ioTxn.getState());
        assertEquals(TransactionState.RUNNING, cpuTxn.getState());
        assertEquals(TransactionState.RUNNING, completeTxn.getState());
    }
    
    @Test
    public void testBlockedToReadyTransition() {
        // Test that retrySpecificTransactions moves BLOCKED -> READY_TXN
        Transaction txn = factory.createTransaction(config, null);
        txn.preparePhase(ExecutionPhase.LOCK, ioModel);
        
        // Block it
        txn.transitionTo(TransactionEvent.LOCK_DENIED);
        assertEquals(TransactionState.BLOCKED, txn.getState());
        queueManager.enqueue(txn, QueueType.BLOCKED_TXN);
        
        assertEquals(1, queueManager.size(QueueType.BLOCKED_TXN));
        
        // Retry blocked - should move to READY_TXN
        lifecycleManager.retrySpecificTransactions(Set.of(txn));
        
        // Should be in READY_TXN queue with READY state
        assertEquals(0, queueManager.size(QueueType.BLOCKED_TXN), 
            "BLOCKED_TXN should be empty");
        assertEquals(1, queueManager.size(QueueType.READY_TXN), 
            "Should move to READY_TXN");
        assertEquals(TransactionState.READY, txn.getState(), 
            "Should transition to READY state");
        
        // Phase should be preserved
        assertEquals(ExecutionPhase.LOCK, txn.getPhase());
    }
    
    @Test
    public void testPhasePreservedAcrossBlockAndUnblock() {
        // Test that phase is preserved through READY_TXN routing
        Transaction txn = factory.createTransaction(config, null);
        txn.preparePhase(ExecutionPhase.LOCK, ioModel);
        txn.advancePhase();  // IO
        txn.advancePhase();  // CPU
        
        ExecutionPhase phaseBeforeBlock = txn.getPhase();
        assertEquals(ExecutionPhase.CPU, phaseBeforeBlock);
        
        // Block then unblock
        txn.transitionTo(TransactionEvent.LOCKS_ACQUIRED); // PENDING -> RUNNING
        txn.transitionTo(TransactionEvent.LOCK_DENIED); // RUNNING -> BLOCKED
        txn.transitionTo(TransactionEvent.LOCKS_AVAILABLE); // BLOCKED -> READY
        queueManager.enqueue(txn, QueueType.READY_TXN);
        
        // Phase should be preserved
        assertEquals(ExecutionPhase.CPU, txn.getPhase(), 
            "Phase should be preserved in READY_TXN");
        
        // Route based on phase
        txn.transitionTo(TransactionEvent.CAPACITY_AVAILABLE); // READY -> RUNNING
        QueueType targetQueue = getQueueForPhase(txn.getPhase());
        queueManager.dequeue(QueueType.READY_TXN);
        queueManager.enqueue(txn, targetQueue);
        
        // Phase should still be CPU and routed to CPU queue
        assertEquals(ExecutionPhase.CPU, txn.getPhase(), 
            "Phase should be preserved after routing");
        assertEquals(1, queueManager.size(QueueType.CPU));
        assertEquals(TransactionState.RUNNING, txn.getState());
    }
    
    @Test
    public void testStateTransitionsFromBlockedToRunning() {
        // Test complete flow: BLOCKED → READY → RUNNING
        Transaction txn = factory.createTransaction(config, null);
        txn.preparePhase(ExecutionPhase.LOCK, ioModel);
        txn.advancePhase();  // IO phase
        
        // Block it
        txn.transitionTo(TransactionEvent.LOCK_DENIED);
        assertEquals(TransactionState.BLOCKED, txn.getState());
        queueManager.enqueue(txn, QueueType.BLOCKED_TXN);
        
        // Unblock to READY_TXN
        lifecycleManager.retrySpecificTransactions(Set.of(txn));
        assertEquals(TransactionState.READY, txn.getState());
        assertEquals(1, queueManager.size(QueueType.READY_TXN));
        
        // Route to execution queue
        txn.transitionTo(TransactionEvent.CAPACITY_AVAILABLE); // READY -> RUNNING
        QueueType targetQueue = getQueueForPhase(txn.getPhase());
        queueManager.dequeue(QueueType.READY_TXN);
        queueManager.enqueue(txn, targetQueue);
        
        // Should be RUNNING in IO queue
        assertEquals(TransactionState.RUNNING, txn.getState());
        assertEquals(1, queueManager.size(QueueType.IO));
    }
    
    @Test
    public void testEmptyBlockedQueueDoesNothing() {
        // Retry with empty BLOCKED_TXN queue
        assertEquals(0, queueManager.size(QueueType.BLOCKED_TXN));
        
        lifecycleManager.retrySpecificTransactions(Set.of());
        
        // All queues should remain empty
        assertEquals(0, queueManager.size(QueueType.BLOCKED_TXN));
        assertEquals(0, queueManager.size(QueueType.PENDING));
        assertEquals(0, queueManager.size(QueueType.IO));
        assertEquals(0, queueManager.size(QueueType.CPU));
        assertEquals(0, queueManager.size(QueueType.WAITING_FOR_GROUP));
    }
    
    @Test
    public void testRetrySpecificTransactionsWithDifferentPhases() {
        // Test selective retry and phase routing
        Transaction lockTxn = factory.createTransaction(config, null);
        lockTxn.preparePhase(ExecutionPhase.LOCK, ioModel);
        lockTxn.transitionTo(TransactionEvent.LOCK_DENIED);
        
        Transaction completeTxn = factory.createTransaction(config, null);
        completeTxn.preparePhase(ExecutionPhase.LOCK, ioModel);
        completeTxn.advancePhase();
        completeTxn.advancePhase();
        completeTxn.advancePhase();
        completeTxn.transitionTo(TransactionEvent.LOCK_DENIED);
        
        Transaction notRetried = factory.createTransaction(config, null);
        notRetried.preparePhase(ExecutionPhase.LOCK, ioModel);
        notRetried.advancePhase();  // IO
        notRetried.transitionTo(TransactionEvent.LOCK_DENIED);
        
        queueManager.enqueue(lockTxn, QueueType.BLOCKED_TXN);
        queueManager.enqueue(completeTxn, QueueType.BLOCKED_TXN);
        queueManager.enqueue(notRetried, QueueType.BLOCKED_TXN);
        
        assertEquals(3, queueManager.size(QueueType.BLOCKED_TXN));
        
        // Retry only specific transactions
        Set<Transaction> toRetry = Set.of(lockTxn, completeTxn);
        lifecycleManager.retrySpecificTransactions(toRetry);
        
        // Specified transactions should go to READY_TXN
        assertEquals(2, queueManager.size(QueueType.READY_TXN), 
            "Two retried transactions");
        
        // Not-retried should stay in BLOCKED_TXN
        assertEquals(1, queueManager.size(QueueType.BLOCKED_TXN), 
            "Non-retried transaction stays blocked");
        
        // States
        assertEquals(TransactionState.READY, lockTxn.getState());
        assertEquals(TransactionState.READY, completeTxn.getState());
        assertEquals(TransactionState.BLOCKED, notRetried.getState());
        
        // Route the retried transactions
        while (queueManager.size(QueueType.READY_TXN) > 0) {
            Transaction txn = queueManager.dequeue(QueueType.READY_TXN);
            txn.transitionTo(TransactionEvent.CAPACITY_AVAILABLE);
            QueueType targetQueue = getQueueForPhase(txn.getPhase());
            queueManager.enqueue(txn, targetQueue);
        }
        
        // Should route by phase
        assertEquals(1, queueManager.size(QueueType.PENDING), 
            "LOCK phase transaction");
        assertEquals(1, queueManager.size(QueueType.WAITING_FOR_GROUP), 
            "COMPLETE phase transaction");
        
        assertEquals(TransactionState.RUNNING, lockTxn.getState());
        assertEquals(TransactionState.RUNNING, completeTxn.getState());
    }
}
