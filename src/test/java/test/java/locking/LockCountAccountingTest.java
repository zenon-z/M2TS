package test.java.locking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import de.tum.tssimulator.bookkeeping.BookkeeperTransaction;
import de.tum.tssimulator.bookkeeping.BookkeepingReason;
import de.tum.tssimulator.bootstrap.Simulator;
import de.tum.tssimulator.bootstrap.SimulatorContext;
import de.tum.tssimulator.config.SimulatorConfig;
import de.tum.tssimulator.config.SimulatorParameters;
import de.tum.tssimulator.core.Model;
import de.tum.tssimulator.core.ModelFactory;
import de.tum.tssimulator.core.Resource;
import de.tum.tssimulator.core.Transaction;
import de.tum.tssimulator.core.TransactionGroup;
import de.tum.tssimulator.execution.StepMode;
import de.tum.tssimulator.locking.core.LockType;
import de.tum.tssimulator.locking.core.OperationLockModes;
import de.tum.tssimulator.locking.policy.LockingProtocol;

/**
 * "A transaction holding k locks reports k."
 *
 * <p>Nothing asserted this before, and it is the numerator of
 * {@code getMeanLockHoldDurationPerLock} - the metric the operation-level ladder's hold-time
 * criterion is built on. It went unasserted long enough for a rename during the
 * {@code Map<String, LockType>} migration to make a bookkeeper report its PARENT's lock count: it
 * compiled, every test passed, and the only visible symptom would have been a silently inflated
 * lock-hold denominator.
 *
 * <p>Written now rather than alongside the per-batch hold-tracking refactor, which replaces this
 * metric's entire input path. A test that exists beforehand catches that refactor's equivalent
 * mistake; one written during it tends to encode whatever the new code happens to do.
 *
 * <p>Two levels, because they fail for different reasons:
 * <ul>
 *   <li><b>Derivation</b> - {@link OperationLockModes} maps a transaction to the right number of
 *       lock ids. Catches the rename class of bug at its source.</li>
 *   <li><b>Accounting</b> - a whole run's recorded lock count equals completions x k. Catches a
 *       correct derivation being recorded against the wrong transaction, or twice, or not at
 *       all.</li>
 * </ul>
 */
class LockCountAccountingTest {

    private static final int K = 8;

    private static List<Model> pool() {
        return new ModelFactory(500).createModels(List.of(1));
    }

    private static Transaction txnWith(String id, List<Resource> resources) {
        Transaction t = new Transaction(id, 0L, resources, new TransactionGroup(), -1);
        t.initializeState();
        return t;
    }

    // --- derivation -------------------------------------------------------------------

    @Test
    void aTransactionWithKDistinctResourcesYieldsKLockIds() {
        Model m = pool().get(0);
        List<Resource> resources = new ArrayList<>();
        for (int i = 1; i <= K; i++) {
            resources.add(new Resource(i, m.properties().get(0)));
        }
        Transaction t = txnWith("T1", resources);

        assertEquals(K, OperationLockModes.lockIds(t, null).size());
        assertEquals(K, OperationLockModes.allExclusive(t, null).size());
    }

    /**
     * The exact shape of the near-miss: a CU holds ONE resource, so it must report one lock, not
     * its parent's k. Nothing else in the suite distinguishes those two numbers.
     */
    @Test
    void aBookkeeperReportsItsOwnLockCountNotItsParents() {
        Model m = pool().get(0);
        List<Resource> parentResources = new ArrayList<>();
        for (int i = 1; i <= K; i++) {
            parentResources.add(new Resource(i, m.properties().get(0)));
        }
        Transaction parent = txnWith("T1", parentResources);

        Resource cuResource = new Resource(99, m.properties().get(0));
        BookkeeperTransaction cu = BookkeeperTransaction.createAcid(
                parent, List.of(cuResource), BookkeepingReason.MODEL_INCONSISTENCY, m, 0);

        assertEquals(K, OperationLockModes.lockIds(parent, null).size(), "parent holds k");
        assertEquals(1, OperationLockModes.lockIds(cu, null).size(),
                "a single-resource CU must report 1, not its parent's k");
    }

    @Test
    void duplicateResourcesCollapseToOneLockAndOrderIsSorted() {
        Model m = pool().get(0);
        Resource a = new Resource(1, m.properties().get(0));
        Resource b = new Resource(2, m.properties().get(0));
        Transaction t = txnWith("T1", List.of(b, a, b, a));

        List<String> ids = OperationLockModes.lockIds(t, null);
        assertEquals(2, ids.size(), "duplicates must dedupe - a lock is held once, not twice");
        assertEquals(ids.stream().sorted().toList(), ids, "sorted, for deterministic acquisition");
        assertEquals(ids, List.copyOf(OperationLockModes.allExclusive(t, null).keySet()),
                "the mode map must preserve that order");
        assertTrue(OperationLockModes.allExclusive(t, null).values().stream()
                .allMatch(v -> v == LockType.EXCLUSIVE));
    }

    // --- accounting -------------------------------------------------------------------

    /**
     * End to end: at MPL=1 there is no contention, so every transaction acquires exactly k locks
     * and completes, and the recorded total must be exactly completions x k.
     *
     * <p>The zero-restart assertion is a premise check, not decoration - with aborts in play,
     * released-on-abort holds are also recorded and the identity stops being exact. If that ever
     * starts failing, this test is measuring something other than what it claims.
     */
    @Test
    void recordedLockCountEqualsCompletionsTimesK() {
        assertLockAccountingExact(LockingProtocol.C2PL);
    }

    /**
     * The same invariant under S2PL, and NOT redundant with the C2PL case: under S2PL
     * {@code tryAdmitWithLockCheck} returns at its protocol branch before reaching the
     * {@code setLockHoldStart} call, so the admission path never seeds tracking at all. Before
     * lock-hold seeding moved into {@code TransactionLockCoordinator.acquireAllLocks}, this metric
     * was a flat zero for the entire S2PL arm - which is the arm the ladder's hold-time criterion
     * gates on. A C2PL-only test cannot see that.
     */
    @Test
    void recordedLockCountEqualsCompletionsTimesKUnderS2PL() {
        assertLockAccountingExact(LockingProtocol.S2PL);
    }

    private void assertLockAccountingExact(LockingProtocol protocol) {
        SimulatorParameters params = SimulatorParameters.builder()
                .totalModels(1)
                .propertiesPerModel(List.of(1))
                .semanticOverlap(0.0)
                .instancePoolSize(2000)
                .totalLocks(2000)
                .useConstantTransactionSize(true)
                .constantTransactionSize(K)
                .maxTransactionModels(1)
                .initialTransactions(1)
                .numberOfIoProcessors(4)
                .numberOfCpuProcessors(4)
                .simulationDurationSeconds(20)
                .stepTimeSlice(10L)
                .transactionSamplingSeed(1000003L)
                .semanticOverlapSeed(1000003L)
                .lockingProtocol(protocol)
                .build();

        List<Model> modelPool = new ModelFactory(params.instancePoolSize)
                .createModels(params.propertiesPerModel);
        SimulatorContext ctx = SimulatorContext.create(SimulatorConfig.fromParameters(params, modelPool));
        new Simulator(ctx, StepMode.FIXED_TIME).run();

        var metrics = ctx.logger().metrics();
        int completed = metrics.getGroupsCompletedTotal();

        assertTrue(completed > 0, "fixture produced no completions - nothing was measured");
        assertEquals(0, metrics.getRestartsTotal(),
                "premise: MPL=1 must be contention-free, or the identity below is not exact");
        // Two assertions, not one: a sum alone cannot distinguish "the right number of records
        // each carrying the wrong count" from "the wrong number of records". Checking the count
        // first means a failure says which.
        assertEquals(completed, metrics.getLockHoldRecordingCount(),
                "every completion must produce exactly one lock-hold record");
        assertEquals((double) completed * K, metrics.getTotalLocksAcquiredCount(), 1e-9,
                "every completed transaction must contribute exactly k to the lock-hold denominator");
    }
}
