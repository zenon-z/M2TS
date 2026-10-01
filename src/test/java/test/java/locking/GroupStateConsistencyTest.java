package test.java.locking;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import de.tum.tssimulator.bookkeeping.BookkeeperTransaction;
import de.tum.tssimulator.core.Model;
import de.tum.tssimulator.core.ModelFactory;
import de.tum.tssimulator.core.Resource;
import de.tum.tssimulator.core.Transaction;
import de.tum.tssimulator.core.TransactionEvent;
import de.tum.tssimulator.core.TransactionGroup;
import de.tum.tssimulator.core.TransactionState;
import de.tum.tssimulator.lifecycle.TransactionLifecycleManager;
import de.tum.tssimulator.locking.coordinator.C2PLTransactionLockCoordinator;
import de.tum.tssimulator.locking.coordinator.TransactionLockCoordinator;
import de.tum.tssimulator.locking.core.LockGranularityMapper;
import de.tum.tssimulator.locking.impl.MapBackedLockWaitTracker;
import de.tum.tssimulator.locking.impl.SS2PLLockManager;
import de.tum.tssimulator.locking.policy.CautiousWaitingPolicy;
import de.tum.tssimulator.monitoring.SimulationLogger;
import de.tum.tssimulator.queue.CentralQueueManager;
import de.tum.tssimulator.queue.QueueType;
import de.tum.tssimulator.state.TransactionGroupRegistry;
import test.java.util.TestIOModelFactory;
import test.java.util.TestObjectFactory;

/**
 * Test that all transactions in a group maintain consistent states,
 * particularly when blocking and unblocking.
 */
public class GroupStateConsistencyTest {
    
    private CentralQueueManager queueManager;
    private SS2PLLockManager lockManager;
    private TransactionLockCoordinator coordinator;
    private SimulationLogger logger;
    private LockGranularityMapper granularityMapper;
    private MapBackedLockWaitTracker waitTracker;
    private TransactionGroupRegistry registry;

    // Fine-grained pool (totalLocks >= totalResources) so X/Y/Z each get their own lock.
    private static final List<Model> MODEL_POOL = new ModelFactory(10).createModels(List.of(3));
    private static Resource resourceX() { return MODEL_POOL.get(0).getOwnedResources().get(0); }
    private static Resource resourceY() { return MODEL_POOL.get(0).getOwnedResources().get(1); }
    private static Resource resourceZ() { return MODEL_POOL.get(0).getOwnedResources().get(2); }

    @BeforeEach
    public void setUp() {
        queueManager = new CentralQueueManager(TestIOModelFactory.createTestIOModel());
        waitTracker = new MapBackedLockWaitTracker();
        registry = new TransactionGroupRegistry();

        CautiousWaitingPolicy policy = new CautiousWaitingPolicy(
            QueueType.BLOCKED_TXN,
            registry,
            waitTracker,
            null  // scheduler not needed for this test
        );

        lockManager = new SS2PLLockManager(policy, waitTracker);
        lockManager.setRegistry(registry);
        granularityMapper = new LockGranularityMapper(1000, MODEL_POOL);
        logger = new SimulationLogger("target/group-state-test.log");
        
        coordinator = new C2PLTransactionLockCoordinator(
            lockManager, queueManager, logger, granularityMapper, null
        );
    }
    
    /**
     * Test (b): When locks are released for one group member, ALL members
     * should be retried together.
     * 
     * Scenario: Group with parent+2 bookkeepers, all blocked on different locks.
     * When one lock is released, entire group should be retried.
     */
    @Test
    public void testUnblockingEntireGroupTogether() {
        // Set up lifecycle manager to manually transition and enqueue for testing
        lockManager.setLifecycleManager(new TransactionLifecycleManager(
            queueManager, null, logger, 0) {
            @Override
            public void retrySpecificTransactions(Set<Transaction> transactions) {
                // Manually transition and enqueue for testing
                for (Transaction txn : transactions) {
                    if (txn.getState() == TransactionState.BLOCKED) {
                        txn.transitionTo(TransactionEvent.LOCKS_AVAILABLE);  // BLOCKED -> READY
                    }
                    queueManager.enqueue(txn, QueueType.READY_TXN);
                }
            }
        });
        
        // Create parent with resource X
        Transaction parent = TestObjectFactory.createTransaction("T1", List.of(resourceX()));
        parent.transitionTo(TransactionEvent.LOCKS_ACQUIRED);
        
        // Create two bookkeepers
        BookkeeperTransaction bk1 = TestObjectFactory.createBookkeeper(parent, "BK1", List.of(resourceY()));
        bk1.transitionTo(TransactionEvent.LOCKS_ACQUIRED);
        
        BookkeeperTransaction bk2 = TestObjectFactory.createBookkeeper(parent, "BK2", List.of(resourceZ()));
        bk2.transitionTo(TransactionEvent.LOCKS_ACQUIRED);
        
        TransactionGroup group = parent.getGroup();
        group.addBookkeeper(bk1);
        group.addBookkeeper(bk2);
        registry.register(group);
        
        // Create T2 that holds lock X
        Transaction t2 = TestObjectFactory.createTransaction("T2", List.of(resourceX()));
        t2.transitionTo(TransactionEvent.LOCKS_ACQUIRED);
        assertTrue(coordinator.acquireAllLocks(t2), "T2 should get lock X");
        
        // Parent tries to acquire X - will be blocked
        boolean acquired = coordinator.acquireAllLocks(parent);
        assertFalse(acquired, "Parent should be blocked on X");
        
        // Entire group should be blocked
        assertEquals(TransactionState.BLOCKED, parent.getState(), "Parent should be BLOCKED");
        assertEquals(TransactionState.BLOCKED, bk1.getState(), "BK1 should be BLOCKED");
        assertEquals(TransactionState.BLOCKED, bk2.getState(), "BK2 should be BLOCKED");
        
        // Verify they're in BLOCKED queue
        queueManager.enqueue(parent, QueueType.BLOCKED_TXN);
        queueManager.enqueue(bk1, QueueType.BLOCKED_TXN);
        queueManager.enqueue(bk2, QueueType.BLOCKED_TXN);
        
        // T2 releases lock X - this should retry ENTIRE group
        lockManager.releaseLocks(t2);
        
        // Check if ALL group members were moved to READY
        int readyCount = 0;
        Transaction txn;
        while ((txn = queueManager.dequeue(QueueType.READY_TXN)) != null) {
            readyCount++;
        }

        // Verify all 3 group members were retried
        assertEquals(3, readyCount, "ALL group members should be in READY queue");
        assertEquals(TransactionState.READY, parent.getState(), "Parent should be READY");
        assertEquals(TransactionState.READY, bk1.getState(), "BK1 should be READY");
        assertEquals(TransactionState.READY, bk2.getState(), "BK2 should be READY");
    }
    
    /**
     * Test: When one member of a group is ABORTED, the admission controller
     * should not try to fire CAPACITY_AVAILABLE on the aborted transaction.
     * 
     * This is the bug: "CAPACITY_AVAILABLE only from READY, not: ABORTED"
     * 
     * Scenario: Group blocked on locks, then locks released (BLOCKED->READY),
     * one member gets aborted while in READY queue, then admission controller
     * tries to admit the group.
     */
    @Test
    public void testAbortedGroupMemberShouldNotBeAdmitted() {
        // Create parent with resource X
        Transaction parent = TestObjectFactory.createTransaction("T1", List.of(resourceX()));
        parent.transitionTo(TransactionEvent.LOCKS_ACQUIRED);
        
        // Create two bookkeepers
        BookkeeperTransaction bk1 = TestObjectFactory.createBookkeeper(parent, "BK1", List.of(resourceY()));
        bk1.transitionTo(TransactionEvent.LOCKS_ACQUIRED);
        
        BookkeeperTransaction bk2 = TestObjectFactory.createBookkeeper(parent, "BK2", List.of(resourceZ()));
        bk2.transitionTo(TransactionEvent.LOCKS_ACQUIRED);
        
        TransactionGroup group = parent.getGroup();
        group.addBookkeeper(bk1);
        group.addBookkeeper(bk2);
        registry.register(group);
        
        // Create T2 that holds lock X (parent will be blocked)
        Transaction t2 = TestObjectFactory.createTransaction("T2", List.of(resourceX()));
        t2.transitionTo(TransactionEvent.LOCKS_ACQUIRED);
        assertTrue(coordinator.acquireAllLocks(t2), "T2 should get lock X");
        
        // Parent tries to acquire X - will be blocked
        boolean acquired = coordinator.acquireAllLocks(parent);
        assertFalse(acquired, "Parent should be blocked on X");
        
        // Entire group should be blocked
        assertEquals(TransactionState.BLOCKED, parent.getState(), "Parent should be BLOCKED");
        assertEquals(TransactionState.BLOCKED, bk1.getState(), "BK1 should be BLOCKED");
        assertEquals(TransactionState.BLOCKED, bk2.getState(), "BK2 should be BLOCKED");
        
        // Setup lifecycle manager to transition BLOCKED->READY when retried
        lockManager.setLifecycleManager(new TransactionLifecycleManager(
            queueManager, null, logger, 0) {
            @Override
            public void retrySpecificTransactions(Set<Transaction> transactions) {
                for (Transaction txn : transactions) {
                    if (txn.getState() == TransactionState.BLOCKED) {
                        txn.transitionTo(TransactionEvent.LOCKS_AVAILABLE);  // BLOCKED -> READY
                    }
                    queueManager.enqueue(txn, QueueType.READY_TXN);
                }
            }
        });
        
        // T2 releases lock X - this transitions entire group to READY
        lockManager.releaseLocks(t2);
        
        // Verify all in READY state
        assertEquals(TransactionState.READY, parent.getState(), "Parent should be READY");
        assertEquals(TransactionState.READY, bk1.getState(), "BK1 should be READY");
        assertEquals(TransactionState.READY, bk2.getState(), "BK2 should be READY");
        
        // Now simulate one bookkeeper getting aborted while in READY queue
        // (e.g., due to timeout, deadlock detection, or other abort condition)
        bk1.transitionTo(TransactionEvent.ABORTED);
        
        // THE BUG: When admission controller dequeues from READY and tries to admit,
        // it will fire CAPACITY_AVAILABLE on all members, including the ABORTED one
        
        // Dequeue the group from READY queue
        Transaction t1 = queueManager.dequeue(QueueType.READY_TXN);
        Transaction t2a = queueManager.dequeue(QueueType.READY_TXN);
        Transaction t3 = queueManager.dequeue(QueueType.READY_TXN);
        
        assertNotNull(t1, "Should have first transaction");
        assertNotNull(t2a, "Should have second transaction");
        assertNotNull(t3, "Should have third transaction");
        
        // Check if any are ABORTED
        boolean hasAbortedMember = false;
        for (Transaction txn : List.of(t1, t2a, t3)) {
            if (txn.getState() == TransactionState.ABORTED) {
                hasAbortedMember = true;
                // Feeding CAPACITY_AVAILABLE to this member would throw
                // IllegalStateException, since that event is only legal from READY.
            }
        }
        
        assertTrue(hasAbortedMember, "Should find ABORTED member in READY queue (demonstrating the bug)");
        
        // THE FIX NEEDED: AbstractAdmissionController.admitReadyTransactionGroup() should:
        // 1. Check if ALL group members are in READY state before firing CAPACITY_AVAILABLE
        // 2. If any member is ABORTED/COMPLETED, skip admission or abort the entire group
        // 3. Remove invalid transactions from the queue before attempting admission
    }
}
