package test.java.core;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import de.tum.tssimulator.bookkeeping.BookkeeperTransaction;
import de.tum.tssimulator.bookkeeping.BookkeepingReason;
import de.tum.tssimulator.core.Model;
import de.tum.tssimulator.core.ModelFactory;
import de.tum.tssimulator.core.Resource;
import de.tum.tssimulator.core.Transaction;
import de.tum.tssimulator.core.TransactionEvent;
import de.tum.tssimulator.core.TransactionGroup;
import de.tum.tssimulator.core.TransactionGroupState;
import de.tum.tssimulator.core.TransactionState;
import de.tum.tssimulator.lifecycle.TransactionGroupAbortCoordinator;
import de.tum.tssimulator.lifecycle.TransactionGroupAbortCoordinator;
import de.tum.tssimulator.locking.core.LockManager;
import de.tum.tssimulator.locking.core.LockType;
import de.tum.tssimulator.locking.policy.LockDecision;
import de.tum.tssimulator.monitoring.SimulationLogger;
import de.tum.tssimulator.queue.QueueManager;
import de.tum.tssimulator.queue.QueueType;
import de.tum.tssimulator.scheduling.TransactionScheduler;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Tests for TransactionGroup operations at different bookkeeper depths.
 * 
 * Tests three scenarios:
 * 1. Single parent (no bookkeepers)
 * 2. Parent with one bookkeeper (depth 1)
 * 3. Parent with cascading bookkeepers (depth 2)
 * 
 * For each scenario, tests:
 * - Abort all members
 * - Complete group
 * - Block all members
 */
public class TransactionGroupOperationsTest {
    
    private MockQueueManager queueManager;
    private MockLockManager lockManager;
    private MockScheduler scheduler;
    private SimulationLogger logger;
    private TransactionGroupAbortCoordinator abortCoordinator;
    
    @BeforeEach
    void setUp() {
        queueManager = new MockQueueManager();
        lockManager = new MockLockManager();
        scheduler = new MockScheduler();
        logger = new SimulationLogger("test-log.txt");
        abortCoordinator = new TransactionGroupAbortCoordinator();
    }
    
    // ==================== ABORT TESTS ====================
    
    @Test
    void testAbortGroup_SingleParent() {
        // Arrange: Single parent, no bookkeepers
        Transaction parent = createTransaction("T1", List.of(res(1)));
        TransactionGroup group = parent.getGroup();
        
        parent.transitionTo(TransactionEvent.LOCKS_ACQUIRED);
        assertEquals(TransactionState.RUNNING, parent.getState());

        // Act: Abort the group (phase 1 - queues rollback work, does not abort yet)
        group.abortAll(queueManager, lockManager, scheduler, 100L, logger, null);
        // Assert: Abort is staged through rollback work
        assertEquals(TransactionState.READY, parent.getState(), 
            "Parent should be queued for rollback");
        
        // Assert: Group marked as aborting until rollback completes
        assertEquals(TransactionGroupState.ABORTING, group.getState(),
            "Group state should be ABORTING until rollback finishes");
        // Assert: Parent removed from queues
        assertTrue(queueManager.removedTransactions.contains(parent),
            "Parent should be removed from queues");

        assertTrue(queueManager.rollbackTransactions.contains(parent),
            "Parent should be enqueued for rollback work");

        assertTrue(lockManager.releasedTransactions.isEmpty(),
            "Locks should stay held until rollback completion");
        
        assertTrue(scheduler.restartedTransactions.isEmpty(),
            "Restart should happen after rollback completion");

        abortCoordinator.completeRollback(group, queueManager, lockManager, scheduler, 100L, logger, null);
        
        assertEquals(TransactionState.ABORTED, parent.getState(), 
            "Parent should be ABORTED once rollback completes");
        assertEquals(TransactionGroupState.ABORTED, group.getState(),
            "Group state should be ABORTED after rollback completion");
        assertTrue(lockManager.releasedTransactions.contains(parent),
            "Parent locks should be released");
        assertTrue(scheduler.restartedTransactions.contains(parent),
            "Parent should be restarted");
    }
    
    @Test
    void testAbortGroup_ParentWithOneBookkeeper() {
        // Arrange: Parent with one bookkeeper (depth 1)
        Transaction parent = createTransaction("T2", List.of(res(1)));
        TransactionGroup group = parent.getGroup();
        
        BookkeeperTransaction bk1 = BookkeeperTransaction.createAcid(
            parent, List.of(res(2)), BookkeepingReason.MODEL_INCONSISTENCY, null, 0
        );
        group.addBookkeeper(bk1);
        
        parent.transitionTo(TransactionEvent.LOCKS_ACQUIRED);
        bk1.transitionTo(TransactionEvent.LOCKS_ACQUIRED);

        // Act: Abort the group (phase 1 - queues rollback work, does not abort yet)
        group.abortAll(queueManager, lockManager, scheduler, 100L, logger, null);
        assertEquals(TransactionState.READY, parent.getState(), 
            "Parent should be queued for rollback");
        assertEquals(TransactionState.RUNNING, bk1.getState(), 
            "Bookkeeper should remain active until rollback completes");
        
        assertEquals(TransactionGroupState.ABORTING, group.getState());
        // Assert: Both removed from queues
        assertTrue(queueManager.removedTransactions.contains(parent),
            "Parent should be removed from queues");
        assertTrue(queueManager.removedTransactions.contains(bk1),
            "Bookkeeper should be removed from queues");

        assertTrue(queueManager.rollbackTransactions.contains(parent),
            "Parent should be enqueued for rollback work");
        assertTrue(lockManager.releasedTransactions.isEmpty(),
            "Locks should not be released before rollback completion");
        assertTrue(scheduler.restartedTransactions.isEmpty(),
            "Restart should be deferred until rollback completion");

        abortCoordinator.completeRollback(group, queueManager, lockManager, scheduler, 100L, logger, null);
        
        // Assert: Both locks released
        assertEquals(TransactionState.ABORTED, parent.getState(), 
            "Parent should be ABORTED after rollback completion");
        assertEquals(TransactionState.ABORTED, bk1.getState(), 
            "Bookkeeper should be ABORTED after rollback completion");
        assertEquals(TransactionGroupState.ABORTED, group.getState());
        assertTrue(lockManager.releasedTransactions.contains(parent),
            "Parent locks should be released");
        assertTrue(lockManager.releasedTransactions.contains(bk1),
            "Bookkeeper locks should be released");
        
        // Assert: Only parent was restarted (not bookkeepers)
        assertTrue(scheduler.restartedTransactions.contains(parent),
            "Parent should be restarted");
        assertFalse(scheduler.restartedTransactions.contains(bk1),
            "Bookkeepers should not be restarted");
    }
    
    @Test
    void testAbortGroup_CascadingBookkeepers() {
        // Arrange: Parent with cascading bookkeepers (depth 2)
        Transaction parent = createTransaction("T3", List.of(res(1)));
        TransactionGroup group = parent.getGroup();
        
        // Depth 1 bookkeeper
        BookkeeperTransaction bk1 = BookkeeperTransaction.createAcid(
            parent, List.of(res(2)), BookkeepingReason.MODEL_INCONSISTENCY, null, 0
        );
        group.addBookkeeper(bk1);
        
        // Depth 2 bookkeeper (child of bk1)
        BookkeeperTransaction bk2 = BookkeeperTransaction.createAcid(
            bk1, List.of(res(3)), BookkeepingReason.MODEL_INCONSISTENCY, null, 0
        );
        group.addBookkeeper(bk2);
        
        // Verify all in same group
        assertEquals(group, parent.getGroup());
        assertEquals(group, bk1.getGroup());
        assertEquals(group, bk2.getGroup());
        
        parent.transitionTo(TransactionEvent.LOCKS_ACQUIRED);
        bk1.transitionTo(TransactionEvent.LOCKS_ACQUIRED);
        bk2.transitionTo(TransactionEvent.LOCKS_ACQUIRED);

        // Act: Abort the group (phase 1 - queues rollback work, does not abort yet)
        group.abortAll(queueManager, lockManager, scheduler, 100L, logger, null);
        assertEquals(TransactionState.READY, parent.getState(), 
            "Parent should be queued for rollback");
        assertEquals(TransactionState.RUNNING, bk1.getState(), 
            "Depth-1 bookkeeper should remain active until rollback completes");
        assertEquals(TransactionState.RUNNING, bk2.getState(), 
            "Depth-2 bookkeeper should remain active until rollback completes");
        
        assertEquals(TransactionGroupState.ABORTING, group.getState());
        
        // Assert: All removed from queues
        assertTrue(queueManager.removedTransactions.contains(parent),
            "Parent should be removed from queues");
        assertTrue(queueManager.removedTransactions.contains(bk1),
            "Depth-1 bookkeeper should be removed from queues");
        assertTrue(queueManager.removedTransactions.contains(bk2),
            "Depth-2 bookkeeper should be removed from queues");

        assertTrue(queueManager.rollbackTransactions.contains(parent),
            "Parent should be enqueued for rollback work");
        assertTrue(lockManager.releasedTransactions.isEmpty(),
            "Locks should remain held until rollback completion");
        assertTrue(scheduler.restartedTransactions.isEmpty(),
            "Restart should wait for rollback completion");

        abortCoordinator.completeRollback(group, queueManager, lockManager, scheduler, 100L, logger, null);
        
        // Assert: All locks released
        assertEquals(TransactionState.ABORTED, parent.getState(), 
            "Parent should be ABORTED after rollback completion");
        assertEquals(TransactionState.ABORTED, bk1.getState(), 
            "Depth-1 bookkeeper should be ABORTED after rollback completion");
        assertEquals(TransactionState.ABORTED, bk2.getState(), 
            "Depth-2 bookkeeper should be ABORTED after rollback completion");
        assertEquals(TransactionGroupState.ABORTED, group.getState());
        assertTrue(lockManager.releasedTransactions.contains(parent),
            "Parent locks should be released");
        assertTrue(lockManager.releasedTransactions.contains(bk1),
            "Depth-1 bookkeeper locks should be released");
        assertTrue(lockManager.releasedTransactions.contains(bk2),
            "Depth-2 bookkeeper locks should be released");
        
        // Assert: Only parent was restarted
        assertTrue(scheduler.restartedTransactions.contains(parent),
            "Parent should be restarted");
        assertEquals(1, scheduler.restartedTransactions.size(),
            "Only parent should be restarted");
    }
    
    // ==================== COMPLETION TESTS ====================
    
    @Test
    void testCompleteGroup_SingleParent() {
        // Arrange: Single parent, no bookkeepers
        Transaction parent = createTransaction("T4", List.of(res(1)));
        TransactionGroup group = parent.getGroup();
        
        parent.transitionTo(TransactionEvent.LOCKS_ACQUIRED);
        parent.transitionTo(TransactionEvent.COMPLETED);
        
        // Act: Mark group as completed
        group.setState(TransactionGroupState.COMPLETED);
        
        // Assert: Group marked as completed
        assertEquals(TransactionGroupState.COMPLETED, group.getState(),
            "Group should be COMPLETED");
        assertEquals(TransactionState.COMPLETED, parent.getState(),
            "Parent should be COMPLETED");
    }
    
    @Test
    void testCompleteGroup_ParentWithOneBookkeeper() {
        // Arrange: Parent with one bookkeeper
        Transaction parent = createTransaction("T5", List.of(res(1)));
        TransactionGroup group = parent.getGroup();
        
        BookkeeperTransaction bk1 = BookkeeperTransaction.createAcid(
            parent, List.of(res(2)), BookkeepingReason.MODEL_INCONSISTENCY, null, 0
        );
        group.addBookkeeper(bk1);
        
        // Complete both
        parent.transitionTo(TransactionEvent.LOCKS_ACQUIRED);
        parent.transitionTo(TransactionEvent.COMPLETED);
        bk1.transitionTo(TransactionEvent.LOCKS_ACQUIRED);
        bk1.transitionTo(TransactionEvent.COMPLETED);
        
        // Simulate completion handler incrementing count
        group.markBookkeeperCompleted();
        
        // Act: Check if group can complete (all members done)
        assertEquals(1, group.getCompletedCount(),
            "Should track 1 completed bookkeeper");
        assertTrue(parent.getState() == TransactionState.COMPLETED,
            "Parent should be COMPLETED");
        
        // Group should be completable when parent + all bookkeepers are done
        group.setState(TransactionGroupState.COMPLETED);
        assertEquals(TransactionGroupState.COMPLETED, group.getState());
    }
    
    @Test
    void testCompleteGroup_CascadingBookkeepers() {
        // Arrange: Parent with cascading bookkeepers
        Transaction parent = createTransaction("T6", List.of(res(1)));
        TransactionGroup group = parent.getGroup();
        
        BookkeeperTransaction bk1 = BookkeeperTransaction.createAcid(
            parent, List.of(res(2)), BookkeepingReason.MODEL_INCONSISTENCY, null, 0
        );
        group.addBookkeeper(bk1);
        
        BookkeeperTransaction bk2 = BookkeeperTransaction.createAcid(
            bk1, List.of(res(3)), BookkeepingReason.MODEL_INCONSISTENCY, null, 0
        );
        group.addBookkeeper(bk2);
        
        // Complete all members
        parent.transitionTo(TransactionEvent.LOCKS_ACQUIRED);
        parent.transitionTo(TransactionEvent.COMPLETED);
        bk1.transitionTo(TransactionEvent.LOCKS_ACQUIRED);
        bk1.transitionTo(TransactionEvent.COMPLETED);
        bk2.transitionTo(TransactionEvent.LOCKS_ACQUIRED);
        bk2.transitionTo(TransactionEvent.COMPLETED);
        
        // Simulate completion handler
        group.markBookkeeperCompleted(); // bk1
        group.markBookkeeperCompleted(); // bk2
        
        // Act: Verify all completed
        assertEquals(2, group.getCompletedCount(),
            "Should track 2 completed bookkeepers");
        assertEquals(2, group.getBookkeepers().size(),
            "Group should have 2 bookkeepers");
        
        // When all members complete, group can be marked complete
        group.setState(TransactionGroupState.COMPLETED);
        assertEquals(TransactionGroupState.COMPLETED, group.getState());
    }
    
    // ==================== BLOCKING TESTS ====================
    
    @Test
    void testBlockGroup_SingleParent() {
        // Arrange: Single parent
        Transaction parent = createTransaction("T7", List.of(res(1)));
        TransactionGroup group = parent.getGroup();
        
        parent.transitionTo(TransactionEvent.LOCKS_ACQUIRED);
        
        // Act: Block the group (when lock contention occurs)
        group.blockAll(logger);
        
        // Assert: Group doesn't track blocking state explicitly
        // but blockAll() clears transactions from processors
        // This is defensive - transactions should stop executing
        assertEquals(TransactionState.RUNNING, parent.getState(),
            "Parent state unchanged by blockAll (just clears from processors)");
    }
    
    @Test
    void testBlockGroup_ParentWithOneBookkeeper() {
        // Arrange: Parent with one bookkeeper
        Transaction parent = createTransaction("T8", List.of(res(1)));
        TransactionGroup group = parent.getGroup();
        
        BookkeeperTransaction bk1 = BookkeeperTransaction.createAcid(
            parent, List.of(res(2)), BookkeepingReason.MODEL_INCONSISTENCY, null, 0
        );
        group.addBookkeeper(bk1);
        
        parent.transitionTo(TransactionEvent.LOCKS_ACQUIRED);
        bk1.transitionTo(TransactionEvent.LOCKS_ACQUIRED);
        
        // Act: Block the group
        group.blockAll(logger);
        
        // Assert: Both still in RUNNING state (blockAll just clears from processors)
        assertEquals(TransactionState.RUNNING, parent.getState());
        assertEquals(TransactionState.RUNNING, bk1.getState());
        
        // The actual state transition to BLOCKED happens via lock coordinator
        // blockAll() is just a defensive measure to stop execution
    }
    
    @Test
    void testBlockGroup_CascadingBookkeepers() {
        // Arrange: Parent with cascading bookkeepers
        Transaction parent = createTransaction("T9", List.of(res(1)));
        TransactionGroup group = parent.getGroup();
        
        BookkeeperTransaction bk1 = BookkeeperTransaction.createAcid(
            parent, List.of(res(2)), BookkeepingReason.MODEL_INCONSISTENCY, null, 0
        );
        group.addBookkeeper(bk1);
        
        BookkeeperTransaction bk2 = BookkeeperTransaction.createAcid(
            bk1, List.of(res(3)), BookkeepingReason.MODEL_INCONSISTENCY, null, 0
        );
        group.addBookkeeper(bk2);
        
        parent.transitionTo(TransactionEvent.LOCKS_ACQUIRED);
        bk1.transitionTo(TransactionEvent.LOCKS_ACQUIRED);
        bk2.transitionTo(TransactionEvent.LOCKS_ACQUIRED);
        
        // Act: Block the group
        group.blockAll(logger);
        
        // Assert: All still in RUNNING state
        // (blockAll() just clears from processors, doesn't change state)
        assertEquals(TransactionState.RUNNING, parent.getState());
        assertEquals(TransactionState.RUNNING, bk1.getState());
        assertEquals(TransactionState.RUNNING, bk2.getState());
        
        // Verify all are in the same group
        assertEquals(group, parent.getGroup());
        assertEquals(group, bk1.getGroup());
        assertEquals(group, bk2.getGroup());
    }
    
    // ==================== HELPER METHODS ====================
    
    private static final Model RESOURCE_MODEL = new ModelFactory(10).createModels(List.of(3)).get(0);

    private static Resource res(int n) {
        return RESOURCE_MODEL.getOwnedResources().get(n - 1);
    }

    private Transaction createTransaction(String id, List<Resource> resources) {
        List<Model> models = new ArrayList<>();
        TransactionGroup group = new TransactionGroup(null);
        Transaction txn = new Transaction(id, resources, group);
        txn.initializeState();
        group.setParent(txn);
        return txn;
    }
    
    // ==================== MOCK CLASSES ====================
    
    private static class MockQueueManager implements QueueManager {
        List<Transaction> removedTransactions = new ArrayList<>();
        List<Transaction> rollbackTransactions = new ArrayList<>();
        List<Transaction> enqueuedWithBackoff = new ArrayList<>();
        
        @Override
        public void enqueue(Transaction t, QueueType type) {
            if (type == QueueType.ROLLBACK) {
                rollbackTransactions.add(t);
            }
        }
        
        @Override
        public void enqueueAll(List<Transaction> ts, QueueType type) {}
        
        @Override
        public void enqueueAll(Iterable<Transaction> ts, QueueType type) {}
        
        @Override
        public Transaction dequeue(QueueType type) { return null; }
        
        @Override
        public boolean isEmpty(QueueType type) { return true; }
        
        @Override
        public int size(QueueType type) { return 0; }
        
        @Override
        public int totalActiveTransactions() { return 0; }
        
        @Override
        public void prepend(Transaction t, QueueType type) {}
        
        @Override
        public boolean contains(Transaction t) { return false; }
        
        @Override
        public boolean remove(Transaction txn) {
            removedTransactions.add(txn);
            return true;
        }
        
        @Override
        public void enqueueWithBackoff(Transaction txn, long currentTime, int retryCount) {
            enqueuedWithBackoff.add(txn);
        }
        
        @Override
        public long getCurrentTime() { return 0; }
    }
    
    private static class MockLockManager implements LockManager {
        List<Transaction> releasedTransactions = new ArrayList<>();
        
        @Override
        public LockDecision requestLock(Transaction t, String resourceId, LockType type) { return null; }
        
        @Override
        public void releaseLocks(Transaction txn) {
            releasedTransactions.add(txn);
        }
        
        @Override
        public void abortTransactionGroup(TransactionGroup group) {}
        
        @Override
        public boolean causesDeadlock(Transaction t, String blockedOn) { return false; }
        
        @Override
        public boolean tryAcquireAllLocks(Transaction t, Map<String, LockType> requested) { return true; }
        
        @Override
        public String checkLocksAvailable(Transaction t, Map<String, LockType> requested) { return null; }
        
        @Override
        public void registerWaiter(Transaction txn, String lockId) {}
        
        @Override
        public Set<Transaction> getWaitersForTransaction(Transaction holder) {
            return Set.of();
        }
    }
    
    private static class MockScheduler extends TransactionScheduler {
        List<Transaction> restartedTransactions = new ArrayList<>();
        
        public MockScheduler() {
            super(null, null, null, null);
        }
        
        @Override
        public Transaction restartTransaction(Transaction oldTxn) {
            restartedTransactions.add(oldTxn);
            // Return a new transaction with incremented retry count
            Transaction newTxn = new Transaction(
                oldTxn.getId(), 
                oldTxn.getResources(),
                oldTxn.getGroup()
            );
            newTxn.initializeState();
            newTxn.incrementLockRetryCount();
            return newTxn;
        }
    }
}
