package test.java.locking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import de.tum.tssimulator.config.SimulatorConfig;
import de.tum.tssimulator.config.SimulatorParameters;
import de.tum.tssimulator.core.Model;
import de.tum.tssimulator.core.ModelFactory;
import de.tum.tssimulator.core.Resource;
import de.tum.tssimulator.core.Transaction;
import de.tum.tssimulator.core.TransactionEvent;
import de.tum.tssimulator.core.TransactionFactory;
import de.tum.tssimulator.core.TransactionGroup;
import de.tum.tssimulator.core.TransactionIdGenerator;
import de.tum.tssimulator.core.program.Operation;
import de.tum.tssimulator.core.program.OperationType;
import de.tum.tssimulator.locking.core.LockType;
import de.tum.tssimulator.locking.core.OperationLockModes;
import de.tum.tssimulator.locking.impl.MapBackedLockWaitTracker;
import de.tum.tssimulator.locking.impl.SS2PLLockManager;
import de.tum.tssimulator.locking.policy.CautiousWaitingPolicy;
import de.tum.tssimulator.locking.policy.GrantedDecision;
import de.tum.tssimulator.locking.policy.HardDenyDecision;
import de.tum.tssimulator.locking.policy.SoftDenyDecision;
import de.tum.tssimulator.model.IOModel;
import de.tum.tssimulator.queue.CentralQueueManager;
import de.tum.tssimulator.queue.QueueType;
import de.tum.tssimulator.scheduling.TransactionScheduler;
import de.tum.tssimulator.state.TransactionGroupRegistry;

/**
 * Compatibility as a lock-manager property, and the three behavioural fixes that came with it.
 *
 * <p><b>These tests are the ONLY verification for those fixes.</b> The validation ladder runs at
 * {@code writeFraction = 1.0}, where every request is EXCLUSIVE - so S/S sharing, the co-reader
 * upgrade denial, and the cautious-waiting self-exclusion are never exercised by it and its
 * "nothing moves" checkpoint says nothing about them. They are only reachable from rung 2 onward.
 * Written accordingly: nothing here leans on a downstream check.
 */
class LockCompatibilityTest {

    private static final List<Model> POOL = new ModelFactory(200).createModels(List.of(1));

    private static Resource res(int instance) {
        return new Resource(instance, POOL.get(0).properties().get(0));
    }

    private static Transaction txn(String id, List<Operation> ops) {
        List<Resource> resources = ops.stream().map(Operation::getResource).distinct().toList();
        Transaction t = new Transaction(id, 0L, resources, new TransactionGroup(), -1, ops, null);
        t.initializeState();
        return t;
    }

    private static Transaction reader(String id, int instance) {
        return txn(id, List.of(new Operation(OperationType.READ, res(instance))));
    }

    private static Transaction writer(String id, int instance) {
        return txn(id, List.of(new Operation(OperationType.WRITE, res(instance))));
    }

    /**
     * A minimally-wired but REAL stack: queue manager, registry and scheduler are all present,
     * because the hard-deny path walks through all three (clock, group lookup, abort/restart).
     * Production always wires them; a fixture that leaves them null tests a state the simulator
     * never reaches, and turns every genuine defect on that path into a NullPointerException about
     * the fixture instead.
     */
    private static SS2PLLockManager lockManager() {
        MapBackedLockWaitTracker tracker = new MapBackedLockWaitTracker();
        SimulatorConfig config = config();
        CentralQueueManager queues = new CentralQueueManager(new IOModel(config));
        TransactionGroupRegistry registry = new TransactionGroupRegistry();
        TransactionScheduler scheduler = new TransactionScheduler(
                config, queues, new TransactionFactory(new TransactionIdGenerator(), 1L), registry);

        SS2PLLockManager lm = new SS2PLLockManager(
                new CautiousWaitingPolicy(QueueType.BLOCKED_TXN, registry, tracker, scheduler), tracker);
        lm.setQueueManager(queues);
        lm.setRegistry(registry);
        return lm;
    }

    private static SimulatorConfig config() {
        return SimulatorConfig.fromParameters(SimulatorParameters.builder()
                .totalModels(1)
                .propertiesPerModel(List.of(1))
                .semanticOverlap(0.0)
                .instancePoolSize(200)
                .totalLocks(200)
                .maxTransactionModels(1)
                .build(), POOL);
    }

    private static Map<String, LockType> req(String lockId, LockType mode) {
        Map<String, LockType> m = new LinkedHashMap<>();
        m.put(lockId, mode);
        return m;
    }

    // --- mode folding ------------------------------------------------------------------

    @Test
    void readFoldsToSharedAndWriteToExclusive() {
        assertEquals(LockType.SHARED,
                OperationLockModes.foldByLockId(reader("T1", 1), null).values().iterator().next());
        assertEquals(LockType.EXCLUSIVE,
                OperationLockModes.foldByLockId(writer("T2", 1), null).values().iterator().next());
    }

    /** A repeat touch escalates: R then W on one resource is one EXCLUSIVE lock, not two locks. */
    @Test
    void readThenWriteOnOneResourceFoldsToOneExclusiveLock() {
        Transaction t = txn("T1", List.of(
                new Operation(OperationType.READ, res(1)),
                new Operation(OperationType.WRITE, res(1))));

        Map<String, LockType> folded = OperationLockModes.foldByLockId(t, null);
        assertEquals(1, folded.size(), "one resource is one lock however many times it is touched");
        assertEquals(LockType.EXCLUSIVE, folded.values().iterator().next(), "stronger mode wins");
    }

    /** Order-independent: the fold must not depend on which operation came first. */
    @Test
    void writeThenReadFoldsToExclusiveToo() {
        Transaction t = txn("T1", List.of(
                new Operation(OperationType.WRITE, res(1)),
                new Operation(OperationType.READ, res(1))));
        assertEquals(LockType.EXCLUSIVE,
                OperationLockModes.foldByLockId(t, null).values().iterator().next());
    }

    @Test
    void allWriteFoldsToAllExclusiveMatchingTheLadderControl() {
        Transaction t = txn("T1", List.of(
                new Operation(OperationType.WRITE, res(1)),
                new Operation(OperationType.WRITE, res(2))));

        assertEquals(OperationLockModes.allExclusive(t, null), OperationLockModes.foldByLockId(t, null),
                "at writeFraction=1.0 folding must reduce exactly to the all-EXCLUSIVE control");
    }

    // --- compatibility parity across all three entry points -----------------------------

    @Test
    void sharedReadersCoexist() {
        SS2PLLockManager lm = lockManager();
        assertInstanceOf(GrantedDecision.class, lm.requestLock(reader("T1", 1), "lockA", LockType.SHARED));
        assertInstanceOf(GrantedDecision.class, lm.requestLock(reader("T2", 1), "lockA", LockType.SHARED));
        assertEquals(2, lm.getLockHolders("lockA").size(), "both readers must hold the lock");
    }

    /**
     * The preclaim entry points used to test only {@code holders.isEmpty()}, so a SHARED holder
     * blocked a SHARED requester. All three must now answer identically.
     */
    @Test
    void allThreeEntryPointsAgreeOnSharedSharing() {
        SS2PLLockManager lm = lockManager();
        lm.requestLock(reader("T1", 1), "lockA", LockType.SHARED);

        Transaction second = reader("T2", 1);
        assertNull(lm.checkLocksAvailable(second, req("lockA", LockType.SHARED)),
                "checkLocksAvailable must not report a SHARED holder as blocking a SHARED request");
        assertTrue(lm.tryAcquireAllLocks(second, req("lockA", LockType.SHARED)),
                "tryAcquireAllLocks must grant a compatible SHARED request");
        assertTrue(lm.getLockHolders("lockA").contains(second),
                "and must actually join the holder set, so release frees it");
    }

    @Test
    void exclusiveStillConflictsEverywhere() {
        SS2PLLockManager lm = lockManager();
        lm.requestLock(writer("T1", 1), "lockA", LockType.EXCLUSIVE);

        Transaction second = writer("T2", 1);
        assertEquals("lockA", lm.checkLocksAvailable(second, req("lockA", LockType.EXCLUSIVE)));
        assertFalse(lm.tryAcquireAllLocks(second, req("lockA", LockType.EXCLUSIVE)));
        assertFalse(lm.requestLock(second, "lockA", LockType.EXCLUSIVE).granted());
    }

    @Test
    void sharedHolderStillBlocksAnExclusiveRequester() {
        SS2PLLockManager lm = lockManager();
        lm.requestLock(reader("T1", 1), "lockA", LockType.SHARED);

        Transaction w = writer("T2", 1);
        assertEquals("lockA", lm.checkLocksAvailable(w, req("lockA", LockType.EXCLUSIVE)));
        assertFalse(lm.requestLock(w, "lockA", LockType.EXCLUSIVE).granted());
    }

    // --- upgrades ----------------------------------------------------------------------

    @Test
    void soleHolderUpgradeIsGranted() {
        SS2PLLockManager lm = lockManager();
        Transaction t = reader("T1", 1);
        lm.requestLock(t, "lockA", LockType.SHARED);

        assertInstanceOf(GrantedDecision.class, lm.requestLock(t, "lockA", LockType.EXCLUSIVE));
        assertEquals(1, lm.getUpgradesGranted());
        assertFalse(lm.tryAcquireAllLocks(writer("T2", 1), req("lockA", LockType.EXCLUSIVE)),
                "after the upgrade the lock is genuinely exclusive");
    }

    /**
     * The latent bug: a SHARED holder with CO-READERS requesting EXCLUSIVE used to fall through to
     * {@code GrantedDecision} - neither upgrading the mode nor denying - so it believed it held an
     * exclusive lock that other readers were still sharing.
     */
    @Test
    void upgradeBlockedByCoReadersIsDeniedNotSilentlyGranted() {
        SS2PLLockManager lm = lockManager();
        Transaction t1 = reader("T1", 1);
        Transaction t2 = reader("T2", 1);
        lm.requestLock(t1, "lockA", LockType.SHARED);
        lm.requestLock(t2, "lockA", LockType.SHARED);

        assertFalse(lm.requestLock(t1, "lockA", LockType.EXCLUSIVE).granted(),
                "a co-reader makes the upgrade impossible - it must not be granted");
        assertEquals(LockType.SHARED, lm.getLockMode("lockA"),
                "and the mode must NOT have changed under the other reader's feet");
        assertEquals(0, lm.getUpgradesGranted());
        assertEquals(1, lm.getUpgradesDenied(), "counted as an upgrade denial, not a plain conflict");
    }

    // --- cautious waiting: self-exclusion ------------------------------------------------

    /**
     * Self-exclusion, kept separable from the co-reader denial by asserting WHICH decision comes
     * back rather than merely that the request was denied.
     *
     * <p>The obvious separation - test it with no co-readers, where the upgrade should be granted -
     * does not work: with a sole holder {@code canGrant} returns true and the request never reaches
     * the wait policy at all, so the fix is unreachable and the test is vacuous. Verified by
     * removing the fix and watching such a test still pass. Self-exclusion is only reachable
     * THROUGH a co-reader denial.
     *
     * <p>What distinguishes the two is the decision. Here the requester is READY (which
     * {@code isBlocked()} counts as blocked) while its co-reader is not blocked at all. The only
     * "blocked holder" is the requester itself, so cautious waiting must SOFT deny - block and wait
     * for the co-reader to leave. Without self-exclusion the requester matches itself, and the
     * result is a HARD deny that aborts its own group for a cycle that does not exist.
     */
    @Test
    void anUpgradingHolderBlockedOnlyByItselfSoftDeniesRatherThanAbortingItsOwnGroup() {
        SS2PLLockManager lm = lockManager();
        Transaction upgrader = reader("T1", 1);
        Transaction coReader = reader("T2", 1);
        lm.requestLock(upgrader, "lockA", LockType.SHARED);
        lm.requestLock(coReader, "lockA", LockType.SHARED);

        // The requester itself is READY; the co-reader is untouched and running.
        upgrader.transitionTo(TransactionEvent.LOCK_DENIED);    // -> BLOCKED
        upgrader.transitionTo(TransactionEvent.LOCKS_AVAILABLE); // -> READY

        var decision = lm.requestLock(upgrader, "lockA", LockType.EXCLUSIVE);

        assertFalse(decision.granted(), "the co-reader still prevents the upgrade");
        assertInstanceOf(SoftDenyDecision.class, decision,
                "the only blocked holder is the requester itself, so this must be a wait - "
                        + "a hard deny here would abort its own group over a non-existent cycle");
    }

    /**
     * The deadlock-shaped case: two co-readers both want EXCLUSIVE. It must RESOLVE rather than
     * hang, and exactly one must die - a version that kills both would pass a liveness-only check.
     */
    @Test
    void twoCoReadersBothUpgradingResolveWithExactlyOneDenied() {
        SS2PLLockManager lm = lockManager();
        Transaction t1 = reader("T1", 1);
        Transaction t2 = reader("T2", 1);
        lm.requestLock(t1, "lockA", LockType.SHARED);
        lm.requestLock(t2, "lockA", LockType.SHARED);

        var first = lm.requestLock(t1, "lockA", LockType.EXCLUSIVE);
        assertFalse(first.granted(), "T1 cannot upgrade past T2");
        assertInstanceOf(SoftDenyDecision.class, first,
                "T2 is not blocked yet, so T1 waits rather than aborting anyone");

        // T1 is now a waiter on lockA. T2's identical request sees a blocked co-holder.
        t1.transitionTo(TransactionEvent.LOCK_DENIED);
        var second = lm.requestLock(t2, "lockA", LockType.EXCLUSIVE);
        assertFalse(second.granted(), "T2 cannot upgrade past T1 either");
        assertInstanceOf(HardDenyDecision.class, second,
                "with T1 blocked while holding, cautious waiting must break the cycle by "
                        + "hard-denying the SECOND requester - not by denying both softly and hanging");

        assertEquals(LockType.SHARED, lm.getLockMode("lockA"), "neither upgrade took effect");
        assertEquals(2, lm.getLockHolders("lockA").size(), "both are still readers");
    }

    // --- NPE on the preclaim hard-deny path ----------------------------------------------

    /**
     * {@code HardDenyDecision.handle} dereferenced its logger unconditionally, and
     * {@code tryAcquireAllLocks} has none to pass - so the first hard deny reached from a preclaim
     * threw NPE instead of aborting. Rare, not unreachable: it needs a preclaim to find a lock
     * whose holder is itself blocked.
     */
    @Test
    void preclaimHardDenyDoesNotThrowWithoutALogger() {
        SS2PLLockManager lm = lockManager();
        Transaction holder = writer("T1", 1);
        lm.requestLock(holder, "lockA", LockType.EXCLUSIVE);
        holder.transitionTo(TransactionEvent.LOCK_DENIED); // blocked while holding -> hard deny path

        Transaction second = writer("T2", 1);
        assertFalse(lm.tryAcquireAllLocks(second, req("lockA", LockType.EXCLUSIVE)),
                "must return false rather than throw NullPointerException");
        assertNotNull(second.getGroup());
    }

    /** Group members share locks, so one must never be treated as blocking another. */
    @Test
    void aGroupMemberIsNotABlocker() {
        SS2PLLockManager lm = lockManager();
        TransactionGroup group = new TransactionGroup();
        Transaction parent = new Transaction("P", 0L, List.of(res(1)), group, -1,
                List.of(new Operation(OperationType.WRITE, res(1))), null);
        parent.initializeState();
        lm.requestLock(parent, "lockA", LockType.EXCLUSIVE);

        assertSame(group, parent.getGroup());
        assertNull(lm.checkLocksAvailable(parent, req("lockA", LockType.EXCLUSIVE)),
                "its own held lock cannot block it");
    }
}
