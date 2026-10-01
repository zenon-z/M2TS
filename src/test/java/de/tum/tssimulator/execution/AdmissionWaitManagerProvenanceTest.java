package de.tum.tssimulator.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import de.tum.tssimulator.core.Transaction;
import de.tum.tssimulator.core.TransactionGroup;
import de.tum.tssimulator.queue.CentralQueueManager;

import test.java.util.TestIOModelFactory;

/**
 * TGS static-locking validation: AdmissionWaitManager's configurable wakeup batch size (B) and
 * the WOKEN provenance tag it's now responsible for setting before re-enqueueing a waiter - see
 * AdmissionAttemptProvenance's javadoc for why that tag matters (it's what lets the plateau
 * mechanism be measured rather than assumed - the OTHER re-attempt channel, RACE_RETRY, is
 * tagged separately by ACIDAdmissionController and has no B-style cap).
 */
class AdmissionWaitManagerProvenanceTest {

    private static Transaction newTxn(String id) {
        Transaction txn = new Transaction(id, List.of(), new TransactionGroup());
        txn.initializeState();
        return txn;
    }

    @Test
    void defaultConstructorUsesBEqualsFour() {
        CentralQueueManager queueManager = new CentralQueueManager(TestIOModelFactory.createTestIOModel());
        AdmissionWaitManager manager = new AdmissionWaitManager(queueManager);

        for (int i = 0; i < 6; i++) {
            manager.registerWaiter("lockA", newTxn("t" + i), 0L);
        }
        assertEquals(6, manager.getWaiterCount("lockA"));

        List<Transaction> woken = manager.wakeWaiters("lockA");
        assertEquals(4, woken.size(), "default B should wake at most 4 waiters per release");
        assertEquals(2, manager.getWaiterCount("lockA"), "remaining waiters should still be queued");
    }

    @Test
    void configurableBBoundsWakeCount() {
        CentralQueueManager queueManager = new CentralQueueManager(TestIOModelFactory.createTestIOModel());
        AdmissionWaitManager manager = new AdmissionWaitManager(queueManager, 2);

        for (int i = 0; i < 5; i++) {
            manager.registerWaiter("lockA", newTxn("t" + i), 0L);
        }

        List<Transaction> woken = manager.wakeWaiters("lockA");
        assertEquals(2, woken.size(), "B=2 should wake exactly 2 waiters per release");
        assertEquals(3, manager.getWaiterCount("lockA"), "the other 3 should remain waiting");
    }

    @Test
    void wakenTransactionsAreTaggedWokenProvenance() {
        CentralQueueManager queueManager = new CentralQueueManager(TestIOModelFactory.createTestIOModel());
        AdmissionWaitManager manager = new AdmissionWaitManager(queueManager, 3);

        Transaction t0 = newTxn("t0");
        Transaction t1 = newTxn("t1");
        assertEquals(AdmissionAttemptProvenance.FRESH, t0.getAdmissionAttemptProvenance(),
                "a transaction never touched by wakeWaiters()/[ADMISSION-RACE] should stay FRESH");

        manager.registerWaiter("lockA", t0, 0L);
        manager.registerWaiter("lockA", t1, 0L);

        List<Transaction> woken = manager.wakeWaiters("lockA");
        assertEquals(2, woken.size());
        assertTrue(woken.stream().allMatch(t -> t.getAdmissionAttemptProvenance() == AdmissionAttemptProvenance.WOKEN),
                "every transaction wakeWaiters() re-enqueues should be tagged WOKEN before the re-enqueue");
    }

    @Test
    void fifoOrderPreservedAcrossWakeup() {
        CentralQueueManager queueManager = new CentralQueueManager(TestIOModelFactory.createTestIOModel());
        AdmissionWaitManager manager = new AdmissionWaitManager(queueManager, 2);

        Transaction first = newTxn("first");
        Transaction second = newTxn("second");
        Transaction third = newTxn("third");
        manager.registerWaiter("lockA", first, 0L);
        manager.registerWaiter("lockA", second, 0L);
        manager.registerWaiter("lockA", third, 0L);

        List<Transaction> woken = manager.wakeWaiters("lockA");
        assertEquals(List.of(first, second), woken, "FIFO: oldest waiters wake first");
    }

    @Test
    void sweepExpiredWaitersFiresIndependentlyOfAnyRelease() {
        CentralQueueManager queueManager = new CentralQueueManager(TestIOModelFactory.createTestIOModel());
        AdmissionWaitManager manager = new AdmissionWaitManager(queueManager, 4, 1000L);

        Transaction stale = newTxn("stale");
        Transaction fresh = newTxn("fresh");
        manager.registerWaiter("lockA", stale, 0L);
        manager.registerWaiter("lockA", fresh, 900L);

        // Below the timeout: nobody's lock ever released, and nothing should fire yet.
        List<Transaction> expiredEarly = manager.sweepExpiredWaiters(999L);
        assertTrue(expiredEarly.isEmpty(), "neither waiter has reached the 1000ms timeout yet");
        assertEquals(2, manager.getWaiterCount("lockA"));

        // Past the timeout for "stale" (registered at 0) but not "fresh" (registered at 900).
        List<Transaction> expired = manager.sweepExpiredWaiters(1000L);
        assertEquals(List.of(stale), expired, "only the waiter past its own timeout should fire");
        assertEquals(AdmissionAttemptProvenance.WAIT_TIMEOUT, stale.getAdmissionAttemptProvenance());
        assertEquals(1, manager.getWaiterCount("lockA"), "the still-fresh waiter must remain registered");
    }

    @Test
    void sweepExpiredWaitersRemovesFromMiddleOfDequeWhenAgesAreMixed() {
        CentralQueueManager queueManager = new CentralQueueManager(TestIOModelFactory.createTestIOModel());
        AdmissionWaitManager manager = new AdmissionWaitManager(queueManager, 4, 500L);

        Transaction old = newTxn("old");
        Transaction mid = newTxn("mid");
        Transaction young = newTxn("young");
        // FIFO order old->mid->young, but only "old" (registered earliest) should expire - a
        // naive front-only check would still work here, but sweepExpiredWaiters() must scan the
        // whole deque since expiry isn't guaranteed to line up with FIFO position in general.
        manager.registerWaiter("lockA", old, 0L);
        manager.registerWaiter("lockA", mid, 400L);
        manager.registerWaiter("lockA", young, 490L);

        List<Transaction> expired = manager.sweepExpiredWaiters(500L);
        assertEquals(List.of(old), expired);
        assertEquals(2, manager.getWaiterCount("lockA"));
    }

    @Test
    void sweepExpiredWaitersBoundsReleaseToMaxWakeupsPerRelease() {
        // Regression test: CrossModeAgreementTest caught an unbounded sweep releasing every
        // expired waiter in one call, producing a synchronized mass-release burst that FIXED_
        // TRANSACTION's uncapped per-step budget absorbed almost for free while FIXED_TIME's
        // capped budget paced normally - a 27x throughput divergence between two step strategies
        // that are supposed to agree within 2x. The fix mirrors wakeWaiters()'s existing
        // thundering-herd cap: at most maxWakeupsPerRelease released per sweep call, regardless
        // of how many are past their timeout.
        CentralQueueManager queueManager = new CentralQueueManager(TestIOModelFactory.createTestIOModel());
        AdmissionWaitManager manager = new AdmissionWaitManager(queueManager, 2, 100L);

        Transaction a = newTxn("a");
        Transaction b = newTxn("b");
        Transaction c = newTxn("c");
        Transaction d = newTxn("d");
        // All four registered before the timeout, spread across two locks - all expire at the
        // same sweep call, but only 2 (maxWakeupsPerRelease) should be released, oldest first.
        manager.registerWaiter("lockA", a, 0L);
        manager.registerWaiter("lockB", b, 1L);
        manager.registerWaiter("lockA", c, 2L);
        manager.registerWaiter("lockB", d, 3L);

        // Sweep once all four are >= the 100ms timeout old, not just the oldest.
        List<Transaction> firstSweep = manager.sweepExpiredWaiters(103L);
        assertEquals(List.of(a, b), firstSweep, "only maxWakeupsPerRelease=2, oldest-registered-first, "
                + "even though all 4 are past their timeout");
        assertEquals(2, manager.getWaiterCount("lockA") + manager.getWaiterCount("lockB"),
                "the other 2 must remain registered for a later sweep, not be dropped");

        List<Transaction> secondSweep = manager.sweepExpiredWaiters(104L);
        assertEquals(List.of(c, d), secondSweep, "the remainder drains on a subsequent call");
    }
}
