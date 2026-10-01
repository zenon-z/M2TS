package test.java.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import de.tum.tssimulator.config.SimulatorConfig;
import de.tum.tssimulator.core.ExecutionPhase;
import de.tum.tssimulator.core.Model;
import de.tum.tssimulator.core.ModelFactory;
import de.tum.tssimulator.core.Resource;
import de.tum.tssimulator.core.Transaction;
import de.tum.tssimulator.core.TransactionAggregateMetrics;
import de.tum.tssimulator.core.TransactionEvent;
import de.tum.tssimulator.core.TransactionState;
import de.tum.tssimulator.lifecycle.TransactionLifecycleManager;
import de.tum.tssimulator.locking.coordinator.C2PLTransactionLockCoordinator;
import de.tum.tssimulator.locking.coordinator.TransactionLockCoordinator;
import de.tum.tssimulator.locking.core.LockGranularityMapper;
import de.tum.tssimulator.locking.core.LockWaitTracker;
import de.tum.tssimulator.locking.impl.MapBackedLockWaitTracker;
import de.tum.tssimulator.locking.impl.SS2PLLockManager;
import de.tum.tssimulator.locking.policy.CautiousWaitingPolicy;
import de.tum.tssimulator.model.IOModel;
import de.tum.tssimulator.monitoring.SimulationLogger;
import de.tum.tssimulator.queue.CentralQueueManager;
import de.tum.tssimulator.queue.QueueType;
import de.tum.tssimulator.scheduling.TransactionScheduler;
import de.tum.tssimulator.state.TransactionGroupRegistry;
import test.java.util.TestIOModelFactory;

import java.util.List;

/**
 * Test that verifies:
 * 1. PENDING -> RUNNING state transitions work
 * 2. RUNNING -> BLOCKED transitions when lock denied
 * 3. BLOCKED -> RUNNING transitions when lock acquired
 * 4. Execution phases (LOCK, IO, CPU, COMPLETE) are preserved during BLOCKED state
 * 5. Transaction resumes at the correct phase after unblocking
 */
public class BlockedStatePhasePreservationTest {

    private CentralQueueManager queueManager;
    private SS2PLLockManager lockManager;
    private TransactionLockCoordinator lockCoordinator;
    private TransactionLifecycleManager lifecycleManager;
    private IOModel ioModel;
    private SimulationLogger logger;
    private ModelFactory modelFactory;
    private List<Model> models;
    private Resource res1;

    @BeforeEach
    void setUp() {
        queueManager = new CentralQueueManager(TestIOModelFactory.createTestIOModel());
        // Create minimal stubs for policy dependencies
        TransactionGroupRegistry registry = new TransactionGroupRegistry();
        TransactionScheduler txnScheduler = null;  // Not needed for this test
        LockWaitTracker waitTracker = new MapBackedLockWaitTracker();
        
        CautiousWaitingPolicy policy = new CautiousWaitingPolicy(
            QueueType.BLOCKED_TXN,
            registry,
            waitTracker,
            txnScheduler
        );
        lockManager = new SS2PLLockManager(policy, waitTracker);
        logger = new SimulationLogger("test-blocked-state.log");
        // Create models using ModelFactory
        modelFactory = new ModelFactory(10);
        models = modelFactory.createModels(List.of(1, 1));  // 2 models, 10 resources each
        res1 = models.get(0).getOwnedResources().get(0);

        lockCoordinator = new C2PLTransactionLockCoordinator(lockManager, queueManager, logger,
                new LockGranularityMapper(1000, models), null);

        SimulatorConfig config = new SimulatorConfig.Builder()
            .modelPool(models)
            .totalModels(models.size())
            .ioTimePerResource(20)
            .cpuTimePerResource(15)
            .lockIOTime(5)
            .lockCPUTimePerResource(5)
            .build();

        lifecycleManager = new TransactionLifecycleManager(queueManager, config, logger, 100);
        lockManager.setLifecycleManager(lifecycleManager);

        // Reset static transaction counters before each test
        TransactionAggregateMetrics.reset();

        ioModel = new IOModel(config);
    }

    @Test
    void testStateTransitions_PendingToRunning() {
        // Test: Transactions start in PENDING, transition to RUNNING via LOCKS_ACQUIRED
        Transaction txn = new Transaction("txn1", List.of(res1), null);
        txn.initializeState();
        
        assertEquals(TransactionState.PENDING, txn.getState(), 
            "Transaction starts in PENDING state");
        
        // Transition to RUNNING
        txn.transitionTo(TransactionEvent.LOCKS_ACQUIRED);
        assertEquals(TransactionState.RUNNING, txn.getState(), 
            "Transaction transitions to RUNNING after LOCKS_ACQUIRED");
    }

    @Test
    void testStateTransitions_RunningToBlocked() {
        // Test: RUNNING -> BLOCKED when lock denied
        Transaction txn1 = new Transaction("txn1", List.of(res1), null);
        txn1.initializeState();
        Transaction txn2 = new Transaction("txn2", List.of(res1), null);
        txn2.initializeState();
        
        // Transition both to RUNNING
        txn1.transitionTo(TransactionEvent.LOCKS_ACQUIRED);
        txn2.transitionTo(TransactionEvent.LOCKS_ACQUIRED);
        
        // First transaction acquires lock
        boolean acquired1 = lockCoordinator.acquireAllLocks(txn1);
        assertTrue(acquired1, "First transaction should acquire lock successfully");
        assertEquals(TransactionState.RUNNING, txn1.getState());
        
        // Second transaction tries to acquire same lock - should be blocked
        boolean acquired2 = lockCoordinator.acquireAllLocks(txn2);
        assertFalse(acquired2, "Second transaction should fail to acquire lock");
        assertEquals(TransactionState.BLOCKED, txn2.getState(), 
            "Second transaction should transition to BLOCKED state");
    }

    @Test
    void testStateTransitions_BlockedToRunning() {
        // Test: BLOCKED -> READY when lock released
        
        Transaction txn1 = new Transaction("txn1", List.of(res1), null);
        txn1.initializeState();
        Transaction txn2 = new Transaction("txn2", List.of(res1), null);
        txn2.initializeState();
        
        // Transition both to RUNNING
        txn1.transitionTo(TransactionEvent.LOCKS_ACQUIRED);
        txn2.transitionTo(TransactionEvent.LOCKS_ACQUIRED);
        
        // First transaction acquires lock
        lockCoordinator.acquireAllLocks(txn1);
        
        // Second transaction gets blocked
        lockCoordinator.acquireAllLocks(txn2);
        assertEquals(TransactionState.BLOCKED, txn2.getState());
        
        // First transaction releases locks
        lockCoordinator.releaseAllLocks(txn1);
        
        // Check that txn2 was moved to READY_TXN with READY state
        // (retrySpecificTransactions is called by lockManager.releaseLocks)
        Transaction retried = queueManager.dequeue(QueueType.READY_TXN);
        assertNotNull(retried, "Blocked transaction should be moved to READY_TXN");
        assertEquals("txn2", retried.getId());
        assertEquals(TransactionState.READY, retried.getState(), 
            "Transaction should be in READY state");
        
        // Fire CAPACITY_AVAILABLE to reach RUNNING
        retried.transitionTo(TransactionEvent.CAPACITY_AVAILABLE);
        assertEquals(TransactionState.RUNNING, retried.getState(), 
            "Transaction should reach RUNNING after CAPACITY_AVAILABLE");
    }

    @Test
    void testPhasePreservation_BlockedInLockPhase() {
        // Test: Transaction blocked during LOCK phase preserves LOCK phase
        
        Transaction txn1 = new Transaction("txn1", List.of(res1), null);
        txn1.initializeState();
        Transaction txn2 = new Transaction("txn2", List.of(res1), null);
        txn2.initializeState();
        
        // Transition both to RUNNING
        txn1.transitionTo(TransactionEvent.LOCKS_ACQUIRED);
        txn2.transitionTo(TransactionEvent.LOCKS_ACQUIRED);
        
        // Set both to LOCK phase
        txn1.preparePhase(ExecutionPhase.LOCK, ioModel);
        txn2.preparePhase(ExecutionPhase.LOCK, ioModel);
        
        assertEquals(ExecutionPhase.LOCK, txn2.getPhase());
        long initialRemainingTime = txn2.getRemainingPhaseTime();
        
        // First transaction acquires lock
        lockCoordinator.acquireAllLocks(txn1);
        
        // Second transaction gets blocked
        lockCoordinator.acquireAllLocks(txn2);
        assertEquals(TransactionState.BLOCKED, txn2.getState());
        
        // Phase should remain LOCK
        assertEquals(ExecutionPhase.LOCK, txn2.getPhase(), 
            "Transaction should stay in LOCK phase when blocked");
        assertEquals(initialRemainingTime, txn2.getRemainingPhaseTime(), 
            "Remaining phase time should be preserved");
        
        // Release lock and unblock
        lockCoordinator.releaseAllLocks(txn1);
        Transaction retried = queueManager.dequeue(QueueType.READY_TXN);
        
        // Phase should still be LOCK after unblocking
        assertEquals(ExecutionPhase.LOCK, retried.getPhase(), 
            "Transaction should resume in LOCK phase after unblocking");
        assertEquals(initialRemainingTime, retried.getRemainingPhaseTime(), 
            "Remaining phase time should be preserved after unblocking");
        assertEquals(TransactionState.READY, retried.getState());
    }

    @Test
    void testPhasePreservation_BlockedInIOPhase() {
        // Test: Transaction blocked during IO phase preserves IO phase
        
        Transaction txn1 = new Transaction("txn1", List.of(res1), null);
        txn1.initializeState();
        Transaction txn2 = new Transaction("txn2", List.of(res1), null);
        txn2.initializeState();
        
        // Transition both to RUNNING
        txn1.transitionTo(TransactionEvent.LOCKS_ACQUIRED);
        txn2.transitionTo(TransactionEvent.LOCKS_ACQUIRED);
        
        // txn1 in LOCK, txn2 in IO (simulating it already had locks but needs more)
        txn1.preparePhase(ExecutionPhase.LOCK, ioModel);
        txn2.preparePhase(ExecutionPhase.IO, ioModel);
        
        // Consume some IO time (10ms out of 20ms total for 1 resource)
        txn2.tick(10, ioModel);
        assertEquals(ExecutionPhase.IO, txn2.getPhase());
        long remainingBeforeBlock = txn2.getRemainingPhaseTime();
        assertEquals(10, remainingBeforeBlock); // 20 - 10 = 10
        
        // First transaction acquires lock
        lockCoordinator.acquireAllLocks(txn1);
        
        // Second transaction tries to acquire additional lock and gets blocked
        lockCoordinator.acquireAllLocks(txn2);
        assertEquals(TransactionState.BLOCKED, txn2.getState());
        
        // Phase should remain IO
        assertEquals(ExecutionPhase.IO, txn2.getPhase(), 
            "Transaction should stay in IO phase when blocked");
        assertEquals(remainingBeforeBlock, txn2.getRemainingPhaseTime(), 
            "Remaining IO time should be preserved");
        
        // While blocked, tick should return 0 (no progress)
        long used = txn2.tick(100, ioModel);
        assertEquals(0, used, "Blocked transaction should not consume time");
        assertEquals(remainingBeforeBlock, txn2.getRemainingPhaseTime(), 
            "Remaining time should not change while blocked");
        
        // Release lock and unblock
        lockCoordinator.releaseAllLocks(txn1);
        // Transaction goes to READY_TXN queue with READY state
        Transaction retried = queueManager.dequeue(QueueType.READY_TXN);
        
        // Phase should still be IO with preserved time
        assertEquals(ExecutionPhase.IO, retried.getPhase(),
            "Transaction should resume in IO phase after unblocking");
        assertEquals(remainingBeforeBlock, retried.getRemainingPhaseTime(), 
            "Remaining IO time should be preserved after unblocking");
        
        // Transition to RUNNING
        assertEquals(TransactionState.READY, retried.getState());
        retried.transitionTo(TransactionEvent.CAPACITY_AVAILABLE);
        assertEquals(TransactionState.RUNNING, retried.getState());
        
        // After reaching RUNNING, should be able to make progress again
        long usedAfterUnblock = retried.tick(5, ioModel);
        assertEquals(5, usedAfterUnblock, "Transaction in READY state should consume time when moved to RUNNING");
        assertEquals(5, retried.getRemainingPhaseTime()); // 10 - 5 = 5
    }

    @Test
    void testPhasePreservation_BlockedInCPUPhase() {
        // Test: Transaction blocked during CPU phase preserves CPU phase
        
        Transaction txn1 = new Transaction("txn1", List.of(res1), null);
        txn1.initializeState();
        Transaction txn2 = new Transaction("txn2", List.of(res1), null);
        txn2.initializeState();
        
        // Transition both to RUNNING
        txn1.transitionTo(TransactionEvent.LOCKS_ACQUIRED);
        txn2.transitionTo(TransactionEvent.LOCKS_ACQUIRED);
        
        txn1.preparePhase(ExecutionPhase.LOCK, ioModel);
        txn2.preparePhase(ExecutionPhase.CPU, ioModel);
        
        // Consume some CPU time (7ms out of 15ms total for 1 resource)
        txn2.tick(7, ioModel);
        assertEquals(ExecutionPhase.CPU, txn2.getPhase());
        long remainingBeforeBlock = txn2.getRemainingPhaseTime();
        assertEquals(8, remainingBeforeBlock); // 15 - 7 = 8
        
        // First transaction acquires lock
        lockCoordinator.acquireAllLocks(txn1);
        
        // Second transaction gets blocked
        lockCoordinator.acquireAllLocks(txn2);
        assertEquals(TransactionState.BLOCKED, txn2.getState());
        
        // Phase should remain CPU
        assertEquals(ExecutionPhase.CPU, txn2.getPhase(), 
            "Transaction should stay in CPU phase when blocked");
        assertEquals(remainingBeforeBlock, txn2.getRemainingPhaseTime(), 
            "Remaining CPU time should be preserved");
        
        // Release lock and unblock
        lockCoordinator.releaseAllLocks(txn1);
        // Transaction goes to READY_TXN queue with READY state
        Transaction retried = queueManager.dequeue(QueueType.READY_TXN);
        
        // Phase should still be CPU
        assertEquals(ExecutionPhase.CPU, retried.getPhase(), 
            "Transaction should resume in CPU phase after unblocking");
        assertEquals(remainingBeforeBlock, retried.getRemainingPhaseTime(), 
            "Remaining CPU time should be preserved after unblocking");
        assertEquals(TransactionState.READY, retried.getState());
        
        // Transition to RUNNING
        retried.transitionTo(TransactionEvent.CAPACITY_AVAILABLE);
        assertEquals(TransactionState.RUNNING, retried.getState());
        
        // After reaching RUNNING, should be able to make progress again
        long usedAfterUnblock = retried.tick(5, ioModel);
        assertEquals(5, usedAfterUnblock, "Transaction in READY state should consume time when moved to RUNNING");
        assertEquals(3, retried.getRemainingPhaseTime()); // 8 - 5 = 3
    }

    @Test
    void testCompleteStateFlow() {
        // Test complete flow: PENDING -> RUNNING -> BLOCKED -> READY -> RUNNING -> COMPLETED
        
        Transaction txn1 = new Transaction("txn1", List.of(res1), null);
        txn1.initializeState();
        Transaction txn2 = new Transaction("txn2", List.of(res1), null);
        txn2.initializeState();
        
        // Transition both to RUNNING
        txn1.transitionTo(TransactionEvent.LOCKS_ACQUIRED);
        txn2.transitionTo(TransactionEvent.LOCKS_ACQUIRED);
        assertEquals(TransactionState.RUNNING, txn2.getState());
        
        // Setup phases
        txn1.preparePhase(ExecutionPhase.LOCK, ioModel);
        txn2.preparePhase(ExecutionPhase.IO, ioModel);
        
        // Block txn2
        lockCoordinator.acquireAllLocks(txn1);
        lockCoordinator.acquireAllLocks(txn2);
        assertEquals(TransactionState.BLOCKED, txn2.getState());
        assertEquals(ExecutionPhase.IO, txn2.getPhase());
        
        // Unblock txn2
        lockCoordinator.releaseAllLocks(txn1);
        // Transaction goes to READY_TXN queue
        Transaction retried = queueManager.dequeue(QueueType.READY_TXN);
        assertEquals(TransactionState.READY, retried.getState());
        retried.transitionTo(TransactionEvent.CAPACITY_AVAILABLE);
        assertEquals(TransactionState.RUNNING, retried.getState());
        assertEquals(ExecutionPhase.IO, retried.getPhase());
        
        // Complete IO and CPU phases
        retried.tick(200, ioModel); // Finish IO
        assertEquals(ExecutionPhase.CPU, retried.getPhase());
        
        retried.tick(150, ioModel); // Finish CPU
        assertEquals(ExecutionPhase.COMPLETE, retried.getPhase());
        // In a unit test without lifecycle manager, must explicitly transition to COMPLETED
        retried.transitionTo(TransactionEvent.COMPLETED);
        assertEquals(TransactionState.COMPLETED, retried.getState());
    }

    @Test
    void testBlockedTransactionDoesNotProgress() {
        // Test: Blocked transactions don't make progress via tick()
        
        Transaction txn1 = new Transaction("txn1", List.of(res1), null);
        txn1.initializeState();
        Transaction txn2 = new Transaction("txn2", List.of(res1), null);
        txn2.initializeState();
        
        // Transition both to RUNNING
        txn1.transitionTo(TransactionEvent.LOCKS_ACQUIRED);
        txn2.transitionTo(TransactionEvent.LOCKS_ACQUIRED);
        
        txn1.preparePhase(ExecutionPhase.LOCK, ioModel);
        txn2.preparePhase(ExecutionPhase.IO, ioModel);
        
        lockCoordinator.acquireAllLocks(txn1);
        lockCoordinator.acquireAllLocks(txn2);
        
        assertEquals(TransactionState.BLOCKED, txn2.getState());
        long remainingBefore = txn2.getRemainingPhaseTime();
        
        // Try to tick - should return 0
        long used1 = txn2.tick(100, ioModel);
        assertEquals(0, used1, "Blocked transaction should return 0 from tick()");
        assertEquals(remainingBefore, txn2.getRemainingPhaseTime(), 
            "Phase time should not decrease while blocked");
        
        // Try again
        long used2 = txn2.tick(50, ioModel);
        assertEquals(0, used2, "Blocked transaction should still return 0");
        assertEquals(remainingBefore, txn2.getRemainingPhaseTime(), 
            "Phase time should still be unchanged");
    }

    @Test
    void testMultipleBlockUnblockCycles() {
        // Test: Transaction can be blocked and moved to READY multiple times
        
        Transaction txn1 = new Transaction("txn1", List.of(res1), null);
        txn1.initializeState();
        Transaction txn2 = new Transaction("txn2", List.of(res1), null);
        txn2.initializeState();
        
        // Transition both to RUNNING
        txn1.transitionTo(TransactionEvent.LOCKS_ACQUIRED);
        txn2.transitionTo(TransactionEvent.LOCKS_ACQUIRED);
        
        txn1.preparePhase(ExecutionPhase.LOCK, ioModel);
        txn2.preparePhase(ExecutionPhase.IO, ioModel);
        
        // First block
        lockCoordinator.acquireAllLocks(txn1);
        lockCoordinator.acquireAllLocks(txn2);
        assertEquals(TransactionState.BLOCKED, txn2.getState());
        
        // Unblock
        lockCoordinator.releaseAllLocks(txn1);
        // Transaction goes to READY_TXN queue
        Transaction retried1 = queueManager.dequeue(QueueType.READY_TXN);
        assertEquals(TransactionState.READY, retried1.getState());
        retried1.transitionTo(TransactionEvent.CAPACITY_AVAILABLE); // READY -> RUNNING
        assertEquals(TransactionState.RUNNING, retried1.getState());
        
        // Make some progress (10ms out of 20ms IO time for 1 resource)
        retried1.tick(10, ioModel);
        assertEquals(10, retried1.getRemainingPhaseTime()); // 20 - 10 = 10
        
        // Block again
        lockCoordinator.acquireAllLocks(txn1);
        lockCoordinator.acquireAllLocks(retried1);
        assertEquals(TransactionState.BLOCKED, retried1.getState());
        assertEquals(10, retried1.getRemainingPhaseTime(), "Time preserved in second block");
        
        // Unblock again
        lockCoordinator.releaseAllLocks(txn1);
        // Transaction goes to READY_TXN queue again
        Transaction retried2 = queueManager.dequeue(QueueType.READY_TXN);
        assertEquals(TransactionState.READY, retried2.getState());
        retried2.transitionTo(TransactionEvent.CAPACITY_AVAILABLE); // READY -> RUNNING
        assertEquals(TransactionState.RUNNING, retried2.getState());
        assertEquals(10, retried2.getRemainingPhaseTime(), "Time preserved after second unblock");
    }
}
