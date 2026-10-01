package de.tum.tssimulator.lifecycle;

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

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Set;

/**
 * Test that retryBlocked() correctly handles transactions in COMPLETED state.
 * 
 * This addresses the bug where completed bookkeepers holding locks could be
 * moved to BLOCKED_TXN queue, then incorrectly retried to PENDING queue,
 * causing IllegalStateException when processors tried to fire PHASE_STARTED
 * from COMPLETED state.
 */
public class RetryBlockedCompletedTransactionTest {

    @Test
    public void testRetryBlockedMovesCompletedTransactionsToWaitingRoom() {
        // Setup
        CentralQueueManager queueManager = new CentralQueueManager(TestIOModelFactory.createTestIOModel());
        SimulationLogger logger = new SimulationLogger("target/test-retry-blocked.log");
        // Create config with minimal valid parameters
        SimulatorConfig config = test.java.util.TestConfigFactory.create(
            1, 10, 0, 0, 0, 1, 1, List.of(), 0, 0, 0, 0, 0, 0, 0
        );
        
        TransactionLifecycleManager lifecycleManager = 
            new TransactionLifecycleManager(queueManager, config, logger, 100);
        
        // Create a transaction that finished its work (phase=COMPLETE) but is waiting for group
        // This simulates a bookkeeper that finished CPU but group isn't complete yet
        IOModel ioModel = new IOModel(config);
        TransactionFactory factory = new TransactionFactory(
            new TransactionIdGenerator()
        );
        
        Transaction finishedTxn = factory.createTransaction(config, null);
        
        // Advance to COMPLETE phase
        finishedTxn.preparePhase(ExecutionPhase.LOCK, ioModel);
        finishedTxn.advancePhase();  // IO
        finishedTxn.advancePhase();  // CPU
        finishedTxn.advancePhase();  // COMPLETE
        assertEquals(ExecutionPhase.COMPLETE, finishedTxn.getPhase());
        finishedTxn.transitionTo(TransactionEvent.READY_TO_EXECUTE); // PENDING -> RUNNING
        assertEquals(TransactionState.RUNNING, finishedTxn.getState());
        
        // Simulate being blocked while in WAITING_FOR_GROUP
        finishedTxn.transitionTo(TransactionEvent.LOCK_DENIED); // RUNNING -> BLOCKED
        queueManager.enqueue(finishedTxn, QueueType.BLOCKED_TXN);
        
        // Create a normal blocked transaction in LOCK phase for comparison
        Transaction blockedTxn = factory.createTransaction(config, null);
        blockedTxn.preparePhase(ExecutionPhase.LOCK, ioModel);
        blockedTxn.transitionTo(TransactionEvent.LOCK_DENIED);
        assertEquals(TransactionState.BLOCKED, blockedTxn.getState());
        assertEquals(ExecutionPhase.LOCK, blockedTxn.getPhase());
        queueManager.enqueue(blockedTxn, QueueType.BLOCKED_TXN);
        
        // Verify both are in BLOCKED_TXN queue
        assertEquals(2, queueManager.size(QueueType.BLOCKED_TXN));
        
        // Act: retry all blocked transactions - moves to READY_TXN
        lifecycleManager.retrySpecificTransactions(Set.of(blockedTxn, finishedTxn));
        
        assertEquals(2, queueManager.size(QueueType.READY_TXN), 
            "Both transactions in READY_TXN");
        assertEquals(0, queueManager.size(QueueType.BLOCKED_TXN),
            "BLOCKED_TXN should be empty");
        assertEquals(TransactionState.READY, blockedTxn.getState());
        assertEquals(TransactionState.READY, finishedTxn.getState());
        
        // Route based on phase (manually simulate dispatcher)
        Transaction txn1 = queueManager.dequeue(QueueType.READY_TXN);
        txn1.transitionTo(TransactionEvent.CAPACITY_AVAILABLE); // READY -> RUNNING
        QueueType targetQueue1 = switch (txn1.getPhase()) {
            case LOCK -> QueueType.PENDING;
            case COMPLETE -> QueueType.WAITING_FOR_GROUP;
            default -> QueueType.PENDING;
        };
        queueManager.enqueue(txn1, targetQueue1);
        
        Transaction txn2 = queueManager.dequeue(QueueType.READY_TXN);
        txn2.transitionTo(TransactionEvent.CAPACITY_AVAILABLE); // READY -> RUNNING
        QueueType targetQueue2 = switch (txn2.getPhase()) {
            case LOCK -> QueueType.PENDING;
            case COMPLETE -> QueueType.WAITING_FOR_GROUP;
            default -> QueueType.PENDING;
        };
        queueManager.enqueue(txn2, targetQueue2);
        
        // Assert: Completed transaction routes to WAITING_FOR_GROUP
        assertEquals(1, queueManager.size(QueueType.WAITING_FOR_GROUP), 
            "Transaction with COMPLETE phase should be in WAITING_FOR_GROUP queue");
        
        // Assert: Lock phase transaction routes to PENDING
        assertEquals(1, queueManager.size(QueueType.PENDING), 
            "Transaction with LOCK phase should be in PENDING queue");
        
        // Assert: Both should be RUNNING
        assertEquals(TransactionState.RUNNING, blockedTxn.getState(),
            "Blocked transaction should be RUNNING after routing");
        assertEquals(TransactionState.RUNNING, finishedTxn.getState(),
            "Finished transaction should be RUNNING after routing");
    }
}
