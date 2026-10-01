package test.java.locking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import de.tum.tssimulator.bookkeeping.BookkeeperTransaction;
import de.tum.tssimulator.bookkeeping.BookkeepingReason;
import de.tum.tssimulator.config.SimulatorConfig;
import de.tum.tssimulator.config.SimulatorParameters;
import de.tum.tssimulator.core.Model;
import de.tum.tssimulator.core.ModelFactory;
import de.tum.tssimulator.core.Resource;
import de.tum.tssimulator.core.Transaction;
import de.tum.tssimulator.core.TransactionFactory;
import de.tum.tssimulator.core.TransactionGroup;
import de.tum.tssimulator.core.TransactionIdGenerator;
import de.tum.tssimulator.core.program.Operation;
import de.tum.tssimulator.core.program.OperationType;
import de.tum.tssimulator.locking.coordinator.S2PLTransactionLockCoordinator;
import de.tum.tssimulator.locking.core.LockGranularityMapper;
import de.tum.tssimulator.locking.core.LockManager;
import de.tum.tssimulator.locking.core.LockType;
import de.tum.tssimulator.locking.impl.MapBackedLockWaitTracker;
import de.tum.tssimulator.locking.impl.SS2PLLockManager;
import de.tum.tssimulator.locking.policy.CautiousWaitingPolicy;
import de.tum.tssimulator.model.IOModel;
import de.tum.tssimulator.monitoring.NullSimulationEventSink;
import de.tum.tssimulator.monitoring.SimulationLogger;
import de.tum.tssimulator.queue.CentralQueueManager;
import de.tum.tssimulator.queue.QueueType;
import de.tum.tssimulator.scheduling.TransactionScheduler;
import de.tum.tssimulator.state.TransactionGroupRegistry;

/**
 * The operation cursor, and the guarantee that it is completely inert at its default.
 *
 * <p>{@code operationBatchSize = 0} must not merely produce the same NUMBERS as before - it must
 * take the same CODE PATH. Rung 0 of the validation ladder asserts bit-identity against a
 * pre-interleaving baseline, and if {@code b = 0} ran one whole-footprint batch through the cursor
 * machinery, that assertion would be comparing the new implementation against itself. So this
 * observes the delegation directly rather than inferring it from equal output.
 */
class OperationCursorTest {

    private static final List<Model> POOL = new ModelFactory(200).createModels(List.of(1));

    private static Resource res(int instance) {
        return new Resource(instance, POOL.get(0).properties().get(0));
    }

    private static Transaction writerOver(String id, int operationCount) {
        List<Operation> ops = new ArrayList<>();
        List<Resource> resources = new ArrayList<>();
        for (int i = 1; i <= operationCount; i++) {
            ops.add(new Operation(OperationType.WRITE, res(i)));
            resources.add(res(i));
        }
        Transaction t = new Transaction(id, 0L, resources, new TransactionGroup(), -1, ops, null);
        t.initializeState();
        return t;
    }

    private static SimulatorConfig config() {
        return SimulatorConfig.fromParameters(SimulatorParameters.builder()
                .totalModels(1).propertiesPerModel(List.of(1)).semanticOverlap(0.0)
                .instancePoolSize(200).totalLocks(200).maxTransactionModels(1)
                .build(), POOL);
    }

    /** Counts which acquisition entry point was taken, so delegation is observed, not inferred. */
    private static final class SpyCoordinator extends S2PLTransactionLockCoordinator {
        int acquireAllCalls = 0;

        SpyCoordinator(LockManager lm, CentralQueueManager queues, SimulationLogger logger,
                LockGranularityMapper mapper, TransactionScheduler scheduler, int batchSize) {
            super(lm, queues, logger, mapper, scheduler, batchSize);
        }

        @Override
        public boolean acquireAllLocks(Transaction txn) {
            acquireAllCalls++;
            return super.acquireAllLocks(txn);
        }
    }

    private static SpyCoordinator coordinator(int batchSize) {
        SimulatorConfig config = config();
        MapBackedLockWaitTracker tracker = new MapBackedLockWaitTracker();
        CentralQueueManager queues = new CentralQueueManager(new IOModel(config));
        TransactionGroupRegistry registry = new TransactionGroupRegistry();
        TransactionScheduler scheduler = new TransactionScheduler(
                config, queues, new TransactionFactory(new TransactionIdGenerator(), 1L), registry);
        SS2PLLockManager lm = new SS2PLLockManager(
                new CautiousWaitingPolicy(QueueType.BLOCKED_TXN, registry, tracker, scheduler), tracker);
        lm.setQueueManager(queues);
        lm.setRegistry(registry);
        // A real logger with a discarding sink: the acquisition path logs every lock request, so a
        // null one would only test the fixture. Production always wires one.
        SimulationLogger logger = new SimulationLogger(new NullSimulationEventSink(), 200, 16);
        return new SpyCoordinator(lm, queues, logger, new LockGranularityMapper(200, POOL), scheduler, batchSize);
    }

    // --- inertness at the default -------------------------------------------------------

    @Test
    void batchSizeZeroDelegatesToAcquireAllLocksAndNeverEngagesTheCursor() {
        SpyCoordinator coordinator = coordinator(0);
        Transaction t = writerOver("T1", 5);

        assertTrue(coordinator.acquireBatchLocks(t));

        assertEquals(1, coordinator.acquireAllCalls,
                "b=0 must call acquireAllLocks literally, not run a batch through the cursor");
        assertFalse(t.hasMoreOperations(),
                "cursor must stay disengaged, so the completion hook never fires");
        assertEquals(0, t.getOperationCursor());
    }

    /** A C2PL coordinator has no cursor at all; its inherited entry point is the legacy call. */
    @Test
    void theBaseImplementationIsAcquireAllLocks() {
        SpyCoordinator coordinator = coordinator(0);
        Transaction t = writerOver("T1", 3);
        coordinator.acquireBatchLocks(t);
        assertEquals(1, coordinator.acquireAllCalls);
    }

    // --- interleaving -------------------------------------------------------------------

    @Test
    void batchSizeOneAcquiresOneOperationAtATime() {
        SpyCoordinator coordinator = coordinator(1);
        Transaction t = writerOver("T1", 4);

        assertTrue(coordinator.acquireBatchLocks(t));
        assertEquals(0, coordinator.acquireAllCalls, "b=1 must NOT fall back to the whole footprint");
        assertEquals(1, t.getOperationCursor());
        assertEquals(1, t.getCurrentBatchSize());
        assertTrue(t.hasMoreOperations());

        for (int expected = 2; expected <= 4; expected++) {
            assertTrue(coordinator.acquireBatchLocks(t));
            assertEquals(expected, t.getOperationCursor());
        }
        assertFalse(t.hasMoreOperations(), "cursor exhausted after the last operation");
    }

    @Test
    void aPartialBatchSizeConsumesThatManyOperations() {
        SpyCoordinator coordinator = coordinator(2);
        Transaction t = writerOver("T1", 5);

        coordinator.acquireBatchLocks(t);
        assertEquals(2, t.getOperationCursor());
        assertEquals(2, t.currentBatch().size());

        coordinator.acquireBatchLocks(t);
        assertEquals(4, t.getOperationCursor());

        coordinator.acquireBatchLocks(t);
        assertEquals(5, t.getOperationCursor(), "final batch is short, not over-run");
        assertEquals(1, t.getCurrentBatchSize());
        assertFalse(t.hasMoreOperations());
    }

    @Test
    void currentBatchReportsTheOperationsJustAcquired() {
        SpyCoordinator coordinator = coordinator(2);
        Transaction t = writerOver("T1", 4);

        coordinator.acquireBatchLocks(t);
        assertEquals(List.of(res(1), res(2)), t.currentBatch().stream().map(Operation::getResource).toList());

        coordinator.acquireBatchLocks(t);
        assertEquals(List.of(res(3), res(4)), t.currentBatch().stream().map(Operation::getResource).toList());
    }

    /**
     * C interleave exactly like parents. Pinned because the completion hook sits before the
     * {@code instanceof BookkeeperTransaction} split, and a future change to that routing could
     * silently make C atomic again while parents kept interleaving - an inconsistency the ladder
     * cannot observe.
     */
    @Test
    void bookkeepersUseTheCursorToo() {
        SpyCoordinator coordinator = coordinator(1);
        Transaction parent = writerOver("P", 2);
        List<Resource> cuResources = List.of(res(50), res(51), res(52));
        BookkeeperTransaction cu = BookkeeperTransaction.createAcid(
                parent, cuResources, BookkeepingReason.MODEL_INCONSISTENCY, POOL.get(0), 0);

        assertEquals(3, cu.getOperations().size(), "a 3-resource CU has 3 operations");
        for (int expected = 1; expected <= 3; expected++) {
            assertTrue(coordinator.acquireBatchLocks(cu));
            assertEquals(expected, cu.getOperationCursor(),
                    "a CU must acquire one operation at a time under b=1, not all three at once");
        }
        assertFalse(cu.hasMoreOperations());
    }

    // --- a denied batch is retried, not skipped -------------------------------------------

    /**
     * The cursor advances on a CLAIM but only commits on a GRANT. A denied batch must be re-served.
     *
     * <p>This was a real defect, and the ladder could not have caught it. The wake path re-enters
     * acquisition through {@code ACIDStrategy.process} - the same entry point as a first admission -
     * so an unconditional advance abandoned the denied batch. Those operations never executed and
     * their locks were never taken, yet {@code getResources().size()} still reported the full
     * footprint at completion, so refs-per-completion stayed at exactly k and nothing looked wrong.
     * It surfaced only as a 1.8% shortfall in charged service demand.
     *
     * <p>Worth stating why that direction is the dangerous one: skipped work makes interleaving look
     * faster. Uncaught, it would have arrived as a favourable rung-1 throughput number.
     */
    @Test
    void aDeniedBatchIsReservedOnTheNextAttemptNotSkipped() {
        SpyCoordinator coordinator = coordinator(1);
        SS2PLLockManager lm = (SS2PLLockManager) coordinator.getLockManager();
        Transaction t = writerOver("T1", 4);

        // A blocker holds res(2) exclusively, so T1's SECOND batch is denied.
        Transaction blocker = writerOver("BLOCK", 4);
        String contested = new LockGranularityMapper(200, POOL).getLockId(res(2));
        assertTrue(lm.requestLock(blocker, contested, LockType.EXCLUSIVE).granted());

        assertTrue(coordinator.acquireBatchLocks(t), "batch 1 (res 1) is uncontested");
        assertEquals(1, t.getOperationCursor());

        assertFalse(coordinator.acquireBatchLocks(t), "batch 2 (res 2) is held by the blocker");
        assertEquals(2, t.getOperationCursor(), "the claim advanced the cursor");
        assertEquals(List.of(res(2)), t.currentBatch().stream().map(Operation::getResource).toList());

        // The wake path calls the same entry point again. It must retry res(2), NOT move to res(3).
        lm.releaseLocks(blocker);
        assertTrue(coordinator.acquireBatchLocks(t), "res 2 is free now");
        assertEquals(List.of(res(2)), t.currentBatch().stream().map(Operation::getResource).toList(),
                "the denied batch is re-served - skipping to res(3) would leave res(2) unlocked and "
                        + "unexecuted while the transaction still claims a 4-resource footprint");
        assertEquals(2, t.getOperationCursor(), "and the cursor did not double-advance");

        // Only now does it move on, and every operation is accounted for exactly once.
        assertTrue(coordinator.acquireBatchLocks(t));
        assertEquals(List.of(res(3)), t.currentBatch().stream().map(Operation::getResource).toList());
        assertTrue(coordinator.acquireBatchLocks(t));
        assertEquals(4, t.getOperationCursor());
        assertFalse(t.hasMoreOperations());
    }

    /**
     * The same contract at the Transaction level, with no lock manager in the way: the cursor
     * advances on a claim, but only a GRANT lets the next claim move past it.
     *
     * <p>Worth pinning here as well as through the coordinator, because it is a trap for callers.
     * A loop that drives {@code takeNextBatch} without signalling grants does not iterate - it
     * re-serves batch one forever. That caught a test of mine before it caught anything else.
     */
    @Test
    void takeNextBatchReservesUntilTheGrantIsSignalled() {
        Transaction t = writerOver("T1", 4);

        assertEquals(List.of(res(1)),
                t.takeNextBatch(1).stream().map(Operation::getResource).toList());
        assertEquals(List.of(res(1)),
                t.takeNextBatch(1).stream().map(Operation::getResource).toList(),
                "no grant signalled, so the same batch is re-served rather than skipped");
        assertEquals(1, t.getOperationCursor(), "and the cursor did not advance twice");

        t.recordBatchAcquired(1, 0L);
        assertEquals(List.of(res(2)),
                t.takeNextBatch(1).stream().map(Operation::getResource).toList(),
                "granted, so the next claim moves on");
        assertEquals(2, t.getOperationCursor());
    }

    /**
     * One hold record per batch, not one per ATTEMPT. A retried batch must not be counted twice, or
     * criterion 3's numerator inflates with contention - which is exactly when it is being read.
     */
    @Test
    void aRetriedBatchRecordsItsHoldOnlyOnce() {
        SpyCoordinator coordinator = coordinator(1);
        SS2PLLockManager lm = (SS2PLLockManager) coordinator.getLockManager();
        Transaction t = writerOver("T1", 3);

        Transaction blocker = writerOver("BLOCK", 3);
        String contested = new LockGranularityMapper(200, POOL).getLockId(res(1));
        assertTrue(lm.requestLock(blocker, contested, LockType.EXCLUSIVE).granted());

        assertFalse(coordinator.acquireBatchLocks(t), "denied on the first attempt");
        assertEquals(0, t.getHeldBatches().size(), "a denied batch holds nothing, so records nothing");

        lm.releaseLocks(blocker);
        assertTrue(coordinator.acquireBatchLocks(t));
        assertEquals(1, t.getHeldBatches().size(), "granted once, recorded once");
        assertEquals(1, t.getLockCountHeld());
    }

    /** The retry mark is inert at b=0, where every acquisition is a single whole-footprint call. */
    @Test
    void theRetryMarkDoesNotDisturbTheWholeFootprintPath() {
        SpyCoordinator coordinator = coordinator(0);
        Transaction t = writerOver("T1", 5);

        assertTrue(coordinator.acquireBatchLocks(t));
        assertTrue(coordinator.acquireBatchLocks(t), "re-requesting the same footprint still grants");
        assertEquals(2, coordinator.acquireAllCalls);
        assertEquals(0, t.getOperationCursor(), "and the cursor is still untouched");
        assertFalse(t.hasMoreOperations());
    }

    // --- cursor bookkeeping --------------------------------------------------------------

    @Test
    void cursorProgressTracksHowFarATransactionGot() {
        SpyCoordinator coordinator = coordinator(1);
        Transaction t = writerOver("T1", 4);
        assertEquals(0.0, t.cursorProgress(), 1e-9);
        coordinator.acquireBatchLocks(t);
        assertEquals(0.25, t.cursorProgress(), 1e-9);
        coordinator.acquireBatchLocks(t);
        coordinator.acquireBatchLocks(t);
        assertEquals(0.75, t.cursorProgress(), 1e-9);
    }

    /** Each batch takes real locks - the cursor is not just an index being advanced. */
    @Test
    void eachBatchActuallyAcquiresItsLocks() {
        SpyCoordinator coordinator = coordinator(1);
        SS2PLLockManager lm = (SS2PLLockManager) coordinator.getLockManager();
        Transaction t = writerOver("T1", 3);

        coordinator.acquireBatchLocks(t);
        assertEquals(1, lm.getLocksHeld(), "one operation acquired means one lock held");
        coordinator.acquireBatchLocks(t);
        assertEquals(2, lm.getLocksHeld());
        coordinator.acquireBatchLocks(t);
        assertEquals(3, lm.getLocksHeld(), "and hold-and-wait keeps the earlier ones");
    }

    /** Modes still come from the operations, per batch. */
    @Test
    void aReadOperationBatchTakesASharedLock() {
        SpyCoordinator coordinator = coordinator(1);
        SS2PLLockManager lm = (SS2PLLockManager) coordinator.getLockManager();
        Transaction t = new Transaction("T1", 0L, List.of(res(1)), new TransactionGroup(), -1,
                List.of(new Operation(OperationType.READ, res(1))), null);
        t.initializeState();

        coordinator.acquireBatchLocks(t);
        String lockId = new LockGranularityMapper(200, POOL).getLockId(res(1));
        assertEquals(LockType.SHARED, lm.getLockMode(lockId));
    }
}
