package de.tum.tssimulator.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.Test;

import de.tum.tssimulator.config.SimulatorConfig;
import de.tum.tssimulator.config.TransactionGenerationMode;
import de.tum.tssimulator.core.program.Operation;
import de.tum.tssimulator.core.program.OperationType;
import de.tum.tssimulator.core.program.ProgramShapeRegistry;
import de.tum.tssimulator.core.program.TransactionProgram;
import de.tum.tssimulator.core.program.TransactionProgramFactory;
import de.tum.tssimulator.locking.core.LockType;
import de.tum.tssimulator.locking.core.OperationLockModes;
import de.tum.tssimulator.model.IOModel;

/**
 * Program mode is where the two cost bases finally diverge, so this is the first real exercise of
 * the step-7 change.
 *
 * <p>Under {@code RANDOM_OPERATIONS} synthesis emits one operation per resource, so "per operation"
 * and "per resource" are numerically identical and the whole validation ladder is blind to which one
 * is in use. The five {@link ProgramShapeRegistry} shapes contain repeat touches - {@code Deposit}
 * is {@code R x, W x} - so here the bases separate: IO/CPU follow operations, lock acquisition
 * follows distinct resources.
 *
 * <p><b>Expectations are pinned per shape, not only as a weighted mean.</b> The 1.79x headline is a
 * prediction derived from the shape weights, and if the measured number disagrees the interesting
 * question is which side is wrong - the arithmetic or the implementation. A per-shape table
 * localises a mismatch to one shape; a single mean only says "the total is off". The weighted
 * figures are then derived FROM this table rather than asserted independently, so the two cannot
 * drift apart.
 */
class ProgramShapeCostBasisTest {

    /** What each shape costs, derived by hand from ProgramShapeRegistry and pinned here. */
    private record Expected(String name, double weight, int operations, int distinctResources,
            int upgrades, LockType foldedMode) {
        /** IO+CPU at 1ms each per operation. */
        int demandPerOperationBasis() {
            return 2 * operations;
        }

        /** What the pre-step-7 basis charged: one unit per declared resource. */
        int demandPerResourceBasis() {
            return 2 * distinctResources;
        }
    }

    private static final List<Expected> EXPECTED = List.of(
            // Balance: R x. One read, one resource, no write - so SHARED and no upgrade.
            new Expected("Balance", 0.30, 1, 1, 0, LockType.SHARED),
            // Deposit / Withdraw: R x, W x. Two operations over ONE resource - the repeat touch
            // that makes the two cost bases differ at all, and one S->X upgrade.
            new Expected("Deposit", 0.15, 2, 1, 1, LockType.EXCLUSIVE),
            new Expected("Withdraw", 0.15, 2, 1, 1, LockType.EXCLUSIVE),
            // Transfer: R x, W x, R y, W y. Four operations over two resources, two upgrades.
            new Expected("Transfer", 0.20, 4, 2, 2, LockType.EXCLUSIVE),
            // Amalgamate: R x, R y, W x, W y. Same counts as Transfer but interleaved, so both
            // reads land before either write - a different upgrade ORDER at the same totals.
            new Expected("Amalgamate", 0.20, 4, 2, 2, LockType.EXCLUSIVE));

    private static List<Model> modelPool() {
        return new ModelFactory(300).createModels(List.of(5, 5));
    }

    private static IOModel ioModel(List<Model> pool, List<TransactionProgram> programs) {
        return new IOModel(new SimulatorConfig.Builder()
                .totalModels(pool.size()).modelPool(pool)
                .ioTimePerResource(1).cpuTimePerResource(1)
                .lockIOTime(1).lockCPUTimePerResource(1)
                .initialTransactionCount(1).totalTransactionSteps(1).simulationDurationSeconds(1)
                .maxTransactionModels(3).maxTransactionResources(5)
                .numIoProcessors(1).numCpuProcessors(1).totalLocks(300)
                .transactionGenerationMode(TransactionGenerationMode.TRANSACTION_PROGRAMS)
                .programPool(programs)
                .build());
    }

    private static Transaction transactionFor(String shapeName, List<Model> pool,
            List<TransactionProgram> programs) {
        TransactionProgram program = programs.stream()
                .filter(p -> p.id().startsWith(shapeName + "@")).findFirst().orElseThrow();
        SimulatorConfig config = new SimulatorConfig.Builder()
                .totalModels(pool.size()).modelPool(pool)
                .ioTimePerResource(1).cpuTimePerResource(1)
                .lockIOTime(1).lockCPUTimePerResource(1)
                .initialTransactionCount(1).totalTransactionSteps(1).simulationDurationSeconds(1)
                .maxTransactionModels(3).maxTransactionResources(5)
                .numIoProcessors(1).numCpuProcessors(1).totalLocks(300)
                .transactionGenerationMode(TransactionGenerationMode.TRANSACTION_PROGRAMS)
                .programPool(List.of(program))
                .build();
        // createTransaction() already initializes state - no initializeState() here.
        return new TransactionFactory(new TransactionIdGenerator(), 17L)
                .createTransaction(config, new TransactionGroup());
    }

    // --- per-shape structure --------------------------------------------------------------

    @Test
    void everyShapeHasTheOperationAndResourceCountsItsExpectationClaims() {
        List<Model> pool = modelPool();
        List<TransactionProgram> programs =
                TransactionProgramFactory.createPrograms(ProgramShapeRegistry.defaultShapes(), pool);

        for (Expected e : EXPECTED) {
            Transaction txn = transactionFor(e.name(), pool, programs);
            assertEquals(e.operations(), txn.getOperations().size(),
                    e.name() + ": operation count");
            assertEquals(e.distinctResources(), txn.getResources().size(),
                    e.name() + ": distinct resource count");
            assertEquals(e.distinctResources(),
                    txn.getOperations().stream().map(Operation::getResource).distinct().count(),
                    e.name() + ": operations must span exactly the declared resources");
        }
    }

    /**
     * The divergence itself, shape by shape. {@code Balance} is the control: one operation on one
     * resource, so the two bases agree there and disagree everywhere else.
     */
    @Test
    void ioAndCpuFollowOperationsWhileLockingFollowsDistinctResources() {
        List<Model> pool = modelPool();
        List<TransactionProgram> programs =
                TransactionProgramFactory.createPrograms(ProgramShapeRegistry.defaultShapes(), pool);
        IOModel io = ioModel(pool, programs);

        for (Expected e : EXPECTED) {
            Transaction txn = transactionFor(e.name(), pool, programs);

            assertEquals(e.operations(), io.getIOTime(txn), e.name() + ": IO is per operation");
            assertEquals(e.operations(), io.getCPUTime(txn), e.name() + ": CPU is per operation");
            assertEquals(e.distinctResources(), txn.getCurrentBatchDistinctResourceCount(),
                    e.name() + ": locking is per distinct resource - a repeat touch of a held lock "
                            + "is a no-op and must not be charged twice");

            boolean shouldDiverge = e.operations() != e.distinctResources();
            assertEquals(shouldDiverge, io.getIOTime(txn) != txn.getCurrentBatchDistinctResourceCount(),
                    e.name() + ": the two bases must differ exactly when there is a repeat touch");
        }
    }

    /** Folded lock modes per shape - one entry per distinct resource, WRITE winning. */
    @Test
    void foldedModesMatchEachShapesWriteSet() {
        List<Model> pool = modelPool();
        List<TransactionProgram> programs =
                TransactionProgramFactory.createPrograms(ProgramShapeRegistry.defaultShapes(), pool);

        for (Expected e : EXPECTED) {
            Transaction txn = transactionFor(e.name(), pool, programs);
            Map<String, LockType> folded = OperationLockModes.foldByLockId(txn, null);

            assertEquals(e.distinctResources(), folded.size(),
                    e.name() + ": one folded entry per distinct resource, not per operation");
            assertTrue(folded.values().stream().allMatch(m -> m == e.foldedMode()),
                    e.name() + ": every lock folds to " + e.foldedMode());
        }
    }

    /**
     * Upgrades are a property of the shape, counted as S-then-X on the same resource. Pinned
     * because it is the one quantity that program mode introduces and {@code RANDOM_OPERATIONS}
     * cannot produce at all: synthesis emits one operation per resource, so no resource is ever
     * touched twice and no upgrade can arise.
     */
    @Test
    void upgradeCountsAreAPropertyOfTheShape() {
        List<Model> pool = modelPool();
        List<TransactionProgram> programs =
                TransactionProgramFactory.createPrograms(ProgramShapeRegistry.defaultShapes(), pool);

        for (Expected e : EXPECTED) {
            Transaction txn = transactionFor(e.name(), pool, programs);

            Map<Resource, OperationType> seen = new LinkedHashMap<>();
            int upgrades = 0;
            for (Operation op : txn.getOperations()) {
                OperationType previous = seen.get(op.getResource());
                if (previous == OperationType.READ && op.getType() == OperationType.WRITE) {
                    upgrades++;
                }
                if (previous == null || op.getType() == OperationType.WRITE) {
                    seen.put(op.getResource(), op.getType());
                }
            }
            assertEquals(e.upgrades(), upgrades, e.name() + ": S->X upgrades");
        }
    }

    // --- the service-demand report ---------------------------------------------------------

    /**
     * The 1.79x headline, derived from the per-shape table rather than asserted on its own.
     *
     * <p>Both figures come from the same {@code EXPECTED} rows the structural tests above check
     * against the real generated transactions, so "the arithmetic" and "the implementation" cannot
     * disagree silently - if a shape's counts were wrong, the tests above fail first and name it.
     */
    @Test
    void weightedServiceDemandShiftsBy179x() {
        double weights = EXPECTED.stream().mapToDouble(Expected::weight).sum();
        assertEquals(1.0, weights, 1e-9, "shape weights must be a distribution");

        double perResource = EXPECTED.stream()
                .mapToDouble(e -> e.weight() * e.distinctResources()).sum();
        double perOperation = EXPECTED.stream()
                .mapToDouble(e -> e.weight() * e.operations()).sum();

        assertEquals(1.40, perResource, 1e-9, "mean declared resources per transaction");
        assertEquals(2.50, perOperation, 1e-9, "mean operations per transaction");
        assertEquals(1.7857, perOperation / perResource, 1e-4,
                "the service-demand shift from moving to the operation basis");
    }

    /**
     * Applies to C2PL exactly as it does to S2PL, and that is deliberate.
     *
     * <p>Cost basis is a property of the work, not of the acquisition discipline - a C2PL baseline
     * charging less for the same operations would not be a fair comparison. But it IS a change to
     * C2PL in program mode, and must not be reported as an S2PL-only effect. The cursor is what is
     * protocol-specific; the cost basis is not, which is why this holds with no cursor engaged.
     */
    @Test
    void theShiftAppliesWithNoCursorEngaged() {
        List<Model> pool = modelPool();
        List<TransactionProgram> programs =
                TransactionProgramFactory.createPrograms(ProgramShapeRegistry.defaultShapes(), pool);
        IOModel io = ioModel(pool, programs);

        Transaction transfer = transactionFor("Transfer", pool, programs);
        assertEquals(0, transfer.getOperationCursor(), "no cursor - the C2PL/legacy path");
        assertEquals(4, io.getIOTime(transfer),
                "4 operations cost 4 even under C2PL, where all 4 are acquired at once");
        assertEquals(2, transfer.getCurrentBatchDistinctResourceCount(),
                "while its 2 locks are still 2");
    }

    /** The report itself, printed so a mismatch is readable per shape rather than as one number. */
    @Test
    void printablePerShapeReport() {
        List<Model> pool = modelPool();
        List<TransactionProgram> programs =
                TransactionProgramFactory.createPrograms(ProgramShapeRegistry.defaultShapes(), pool);
        IOModel io = ioModel(pool, programs);

        StringBuilder report = new StringBuilder(String.format(Locale.US,
                "%n%-12s %7s %5s %5s %9s %9s %7s%n", "shape", "weight", "ops", "res",
                "per-res", "per-op", "ratio"));
        double weightedRes = 0.0;
        double weightedOps = 0.0;

        for (Expected e : EXPECTED) {
            Transaction txn = transactionFor(e.name(), pool, programs);
            long measured = io.getIOTime(txn) + io.getCPUTime(txn);
            assertEquals(e.demandPerOperationBasis(), measured,
                    e.name() + ": measured demand must match the per-shape expectation");

            weightedRes += e.weight() * e.distinctResources();
            weightedOps += e.weight() * e.operations();
            report.append(String.format(Locale.US, "%-12s %7.2f %5d %5d %9d %9d %7.3f%n",
                    e.name(), e.weight(), e.operations(), e.distinctResources(),
                    e.demandPerResourceBasis(), e.demandPerOperationBasis(),
                    (double) e.demandPerOperationBasis() / e.demandPerResourceBasis()));
        }
        report.append(String.format(Locale.US, "%-12s %7.2f %5.2f %5.2f %9.2f %9.2f %7.3f%n",
                "WEIGHTED", 1.0, weightedOps, weightedRes,
                2 * weightedRes, 2 * weightedOps, weightedOps / weightedRes));
        System.out.println(report);

        assertEquals(1.7857, weightedOps / weightedRes, 1e-4);
    }
}
