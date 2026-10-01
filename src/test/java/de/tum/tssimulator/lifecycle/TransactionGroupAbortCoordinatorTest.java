package de.tum.tssimulator.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import de.tum.tssimulator.bookkeeping.BookkeeperTransaction;
import de.tum.tssimulator.bookkeeping.BookkeepingReason;
import de.tum.tssimulator.core.Model;
import de.tum.tssimulator.core.ModelFactory;
import de.tum.tssimulator.core.Resource;
import de.tum.tssimulator.core.Transaction;
import de.tum.tssimulator.core.TransactionGroup;
import de.tum.tssimulator.locking.core.LockManager;
import de.tum.tssimulator.locking.core.LockType;
import de.tum.tssimulator.locking.policy.LockDecision;
import de.tum.tssimulator.monitoring.SimulationEventSink;
import de.tum.tssimulator.monitoring.SimulationLogger;
import de.tum.tssimulator.queue.QueueManager;
import de.tum.tssimulator.queue.QueueType;
import de.tum.tssimulator.scheduling.TransactionScheduler;

/**
 * wasted_cpu_io_seconds: TransactionGroupAbortCoordinator.completeRollback() must accumulate
 * every aborted member's already-spent IO/CPU time (read right before it becomes unreachable
 * via ABORTED), and must never accumulate time for members that were never aborted.
 */
class TransactionGroupAbortCoordinatorTest {

    private static class NoOpSink implements SimulationEventSink {
        @Override public void writeLine(String line) { }
        @Override public void close() { }
    }

    private static class NoOpQueueManager implements QueueManager {
        @Override public void enqueue(Transaction txn, QueueType type) { }
        @Override public void enqueueAll(Iterable<Transaction> txns, QueueType type) { }
        @Override public void enqueueAll(List<Transaction> ts, QueueType type) { }
        @Override public Transaction dequeue(QueueType type) { return null; }
        @Override public void prepend(Transaction txn, QueueType type) { }
        @Override public boolean contains(Transaction txn) { return false; }
        @Override public boolean isEmpty(QueueType type) { return true; }
        @Override public int size(QueueType type) { return 0; }
        @Override public int totalActiveTransactions() { return 0; }
        @Override public boolean remove(Transaction txn) { return false; }
        @Override public void enqueueWithBackoff(Transaction txn, long currentTime, int retryCount) { }
        @Override public long getCurrentTime() { return 0; }
    }

    private static class NoOpLockManager implements LockManager {
        @Override public LockDecision requestLock(Transaction t, String resourceId, LockType type) { return null; }
        @Override public void releaseLocks(Transaction t) { }
        @Override public void abortTransactionGroup(TransactionGroup group) { }
        @Override public boolean causesDeadlock(Transaction t, String blockedOn) { return false; }
        @Override public boolean tryAcquireAllLocks(Transaction t, Map<String, LockType> requested) { return true; }
        @Override public String checkLocksAvailable(Transaction t, Map<String, LockType> requested) { return null; }
        @Override public void registerWaiter(Transaction txn, String lockId) { }
        @Override public Set<Transaction> getWaitersForTransaction(Transaction holder) { return new HashSet<>(); }
    }

    /** restartTransaction() is overridden to avoid needing a real TransactionFactory/registry -
     * completeRollback() only needs *a* replacement transaction to enqueue, not a faithful one. */
    private static class StubScheduler extends TransactionScheduler {
        StubScheduler() { super(null, null, null, null); }

        @Override
        public Transaction restartTransaction(Transaction original) {
            TransactionGroup newGroup = new TransactionGroup();
            Transaction restarted = new Transaction("restarted-" + original.getId(), original.getResources(), newGroup);
            newGroup.setParent(restarted);
            return restarted;
        }
    }

    private static Model model() {
        return new ModelFactory(5).createModels(List.of(3)).get(0);
    }

    @Test
    void abortingAGroupAccumulatesWastedIoAndCpuFromParentAndBookkeepers() {
        Model model = model();
        List<Resource> resources = model.getOwnedResources().subList(0, 2);

        TransactionGroup group = new TransactionGroup();
        Transaction parent = new Transaction("P1", resources, group);
        group.setParent(parent);
        parent.addSimulatedTime(1000, QueueType.IO);
        parent.addSimulatedTime(500, QueueType.CPU);

        BookkeeperTransaction bookkeeper = BookkeeperTransaction.createAcid(
                parent, resources, BookkeepingReason.MODEL_INCONSISTENCY, model, 1);
        bookkeeper.addSimulatedTime(300, QueueType.IO);
        bookkeeper.addSimulatedTime(150, QueueType.CPU);
        group.addBookkeeper(bookkeeper);

        SimulationLogger logger = new SimulationLogger(new NoOpSink(), 100, 10);
        TransactionGroupAbortCoordinator coordinator = new TransactionGroupAbortCoordinator();

        coordinator.completeRollback(group, new NoOpQueueManager(), new NoOpLockManager(), new StubScheduler(),
                1_000L, logger, null);

        assertEquals(1000.0 + 300.0, logger.metrics().getWastedIoTime(), 1e-9);
        assertEquals(500.0 + 150.0, logger.metrics().getWastedCpuTime(), 1e-9);
    }

    @Test
    void aGroupThatIsNeverAbortedDoesNotContributeWastedTime() {
        Model model = model();
        List<Resource> resources = model.getOwnedResources().subList(0, 2);

        SimulationLogger logger = new SimulationLogger(new NoOpSink(), 100, 10);
        TransactionGroupAbortCoordinator coordinator = new TransactionGroupAbortCoordinator();

        // Group A: aborted, contributes its own IO/CPU time.
        TransactionGroup abortedGroup = new TransactionGroup();
        Transaction abortedParent = new Transaction("ABORTED1", resources, abortedGroup);
        abortedGroup.setParent(abortedParent);
        abortedParent.addSimulatedTime(400, QueueType.IO);
        abortedParent.addSimulatedTime(200, QueueType.CPU);
        coordinator.completeRollback(abortedGroup, new NoOpQueueManager(), new NoOpLockManager(),
                new StubScheduler(), 1_000L, logger, null);

        // Group B: "completed" - never passed through completeRollback/abortGroup. Its
        // accumulated IO/CPU time must not appear in the wasted-work totals.
        TransactionGroup completedGroup = new TransactionGroup();
        Transaction completedParent = new Transaction("COMPLETED1", resources, completedGroup);
        completedGroup.setParent(completedParent);
        completedParent.addSimulatedTime(9999, QueueType.IO);
        completedParent.addSimulatedTime(9999, QueueType.CPU);

        assertEquals(400.0, logger.metrics().getWastedIoTime(), 1e-9,
                "only the aborted group's time should be counted");
        assertEquals(200.0, logger.metrics().getWastedCpuTime(), 1e-9,
                "only the aborted group's time should be counted");
    }
}
