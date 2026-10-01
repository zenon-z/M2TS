package test.java.monitoring;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import de.tum.tssimulator.core.Model;
import de.tum.tssimulator.core.ModelFactory;
import de.tum.tssimulator.core.Resource;
import de.tum.tssimulator.core.Transaction;
import de.tum.tssimulator.core.TransactionGroup;
import de.tum.tssimulator.locking.coordinator.C2PLTransactionLockCoordinator;
import de.tum.tssimulator.locking.core.LockGranularityMapper;
import de.tum.tssimulator.locking.core.LockManager;
import de.tum.tssimulator.locking.core.LockType;
import de.tum.tssimulator.locking.policy.LockDecision;
import de.tum.tssimulator.locking.policy.SoftDenyDecision;
import de.tum.tssimulator.monitoring.LogEvent;
import de.tum.tssimulator.monitoring.SimulationLogger;
import de.tum.tssimulator.queue.CentralQueueManager;
import de.tum.tssimulator.queue.QueueType;
import test.java.util.TestIOModelFactory;

class StructuredLoggingTest {

    static class StubLogger extends SimulationLogger {
        List<LogEvent> events = new ArrayList<>();
        public StubLogger() { super("target/structured-logging-test.log"); }
        @Override public void logEvent(LogEvent event) { events.add(event); }
    }

    static class AlwaysDenyLockManager implements LockManager {
        @Override public void abortTransactionGroup(TransactionGroup group) { }
        @Override public boolean causesDeadlock(Transaction tx, String res) { return false; }
        @Override public void releaseLocks(Transaction tx) { }
        @Override public LockDecision requestLock(Transaction tx, String resource, LockType type) {
            return new SoftDenyDecision(QueueType.BLOCKED_TXN);
        }
        @Override public boolean tryAcquireAllLocks(Transaction t, Map<String, LockType> requested) {
            return false;
        }
        @Override public String checkLocksAvailable(Transaction t, Map<String, LockType> requested) {
            return "DENIED";
        }
        @Override public void registerWaiter(Transaction txn, String lockId) {}
        @Override public Set<Transaction> getWaitersForTransaction(Transaction holder) { return Set.of(); }
    }

    @Test
    void transactionLockDeniedEmitsRequestedThenDenied() {
        StubLogger logger = new StubLogger();
        List<Model> modelPool = new ModelFactory(1000).createModels(List.of(1));
        Resource r1 = modelPool.get(0).getOwnedResources().get(0);
        TransactionGroup group = new TransactionGroup(null);
        Transaction tx = new Transaction("T1", 0, List.of(r1), group);
        tx.initializeState();

        C2PLTransactionLockCoordinator coord = new C2PLTransactionLockCoordinator(
                new AlwaysDenyLockManager(),
                new CentralQueueManager(TestIOModelFactory.createTestIOModel()),
                logger,
                new LockGranularityMapper(1000, modelPool),
                null
        );

        boolean granted = coord.acquireAllLocks(tx);
        assertFalse(granted);

        List<LogEvent> ev = logger.events;
        assertTrue(ev.stream().anyMatch(e -> e.getType() == LogEvent.Type.LOCK_REQUESTED && "T1".equals(e.getTransactionId())));
        assertTrue(ev.stream().anyMatch(e -> e.getType() == LogEvent.Type.LOCK_DENIED && "T1".equals(e.getTransactionId())));
    }
}
