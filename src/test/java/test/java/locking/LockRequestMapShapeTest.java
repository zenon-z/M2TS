package test.java.locking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import de.tum.tssimulator.core.Model;
import de.tum.tssimulator.core.ModelFactory;
import de.tum.tssimulator.core.Resource;
import de.tum.tssimulator.core.Transaction;
import de.tum.tssimulator.core.TransactionGroup;
import de.tum.tssimulator.locking.core.LockType;
import de.tum.tssimulator.locking.impl.MapBackedLockWaitTracker;
import de.tum.tssimulator.locking.impl.SS2PLLockManager;
import de.tum.tssimulator.locking.policy.CautiousWaitingPolicy;
import de.tum.tssimulator.queue.QueueType;

/**
 * The preclaim entry points take a {@code Map<String, LockType>} rather than a
 * {@code List<String>}. These assert the map is actually CONSUMED.
 *
 * <p>Why this needs saying: every fake {@code LockManager} in the test tree ignores this argument
 * and returns a constant, so they compile and pass against any shape of call - including a caller
 * that hands over an empty map. And an empty map is not a loud failure: every loop in
 * {@code tryAcquireAllLocks} is over {@code requested.keySet()}, so an empty one acquires nothing,
 * conflicts with nothing, and returns {@code true}. Admission would appear to succeed while no lock
 * was ever taken. Nothing in the fake-based tests can see that; these can.
 *
 * <p>The caller side (that {@code ACIDAdmissionController} builds a correctly-sized map) is covered
 * by the step-3 checkpoint instead: C2PL must stay bit-identical, and an empty map would send
 * throughput through the roof rather than leave it unchanged.
 */
class LockRequestMapShapeTest {

    private static Transaction txn(String id) {
        List<Model> pool = new ModelFactory(20).createModels(List.of(2));
        Resource r = new Resource(1, pool.get(0).properties().get(0));
        Transaction t = new Transaction(id, 0L, List.of(r), new TransactionGroup(), -1);
        t.initializeState();
        return t;
    }

    private static SS2PLLockManager lockManager() {
        MapBackedLockWaitTracker tracker = new MapBackedLockWaitTracker();
        // Null registry/scheduler are fine: none of these tests reaches a denial, which is the
        // only path that touches them.
        return new SS2PLLockManager(
                new CautiousWaitingPolicy(QueueType.BLOCKED_TXN, null, tracker, null), tracker);
    }

    private static Map<String, LockType> exclusive(String... lockIds) {
        Map<String, LockType> requested = new LinkedHashMap<>();
        for (String id : lockIds) {
            requested.put(id, LockType.EXCLUSIVE);
        }
        return requested;
    }

    @Test
    void tryAcquireAllLocksAcquiresEveryEntryInTheMap() {
        SS2PLLockManager lm = lockManager();
        Transaction t = txn("T1");

        assertTrue(lm.tryAcquireAllLocks(t, exclusive("lockA", "lockB", "lockC")));

        assertEquals(3, lm.getLocksHeld(),
                "all three requested locks must be held - if the map were ignored this would be 0");
        for (String lockId : List.of("lockA", "lockB", "lockC")) {
            assertTrue(lm.getLockHolders(lockId).contains(t), lockId + " should be held by T1");
        }
    }

    /**
     * Pins the silent-success hazard rather than asserting it is desirable: an empty request
     * trivially "succeeds". Documented here so that if a caller ever regresses to passing an empty
     * map, the reason admission looks healthy while holding nothing is already written down.
     */
    @Test
    void emptyRequestSucceedsWithoutAcquiringAnything() {
        SS2PLLockManager lm = lockManager();

        assertTrue(lm.tryAcquireAllLocks(txn("T1"), exclusive()),
                "vacuously true - there is nothing to conflict with");
        assertEquals(0, lm.getLocksHeld(), "and nothing is actually held");
    }

    @Test
    void checkLocksAvailableReportsAHeldLockFromTheMap() {
        SS2PLLockManager lm = lockManager();
        Transaction holder = txn("T1");
        assertTrue(lm.tryAcquireAllLocks(holder, exclusive("lockA")));

        Transaction other = txn("T2");
        assertEquals("lockA", lm.checkLocksAvailable(other, exclusive("lockA")),
                "the blocking lock must be found via the map's keys");
        assertEquals(null, lm.checkLocksAvailable(other, exclusive("lockZ")),
                "an unheld lock is available");
    }

    /**
     * Iteration order is acquisition order, so the map must preserve what the caller put in.
     * {@code ACIDAdmissionController} sorts its lock ids precisely so this order is deterministic,
     * and a caller handing over an unordered map would silently discard that.
     */
    @Test
    void requestOrderIsPreserved() {
        Map<String, LockType> requested = exclusive("lockC", "lockA", "lockB");
        assertEquals(List.of("lockC", "lockA", "lockB"), List.copyOf(requested.keySet()));
        assertFalse(requested.isEmpty());
    }
}
