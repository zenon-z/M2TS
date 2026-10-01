package test.java.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import de.tum.tssimulator.config.SimulatorConfig;
import de.tum.tssimulator.config.SimulatorParameters;
import de.tum.tssimulator.core.Model;
import de.tum.tssimulator.core.ModelFactory;
import de.tum.tssimulator.core.Resource;
import de.tum.tssimulator.core.Transaction;
import de.tum.tssimulator.core.TransactionGroup;
import de.tum.tssimulator.core.program.Operation;
import de.tum.tssimulator.core.program.OperationType;
import de.tum.tssimulator.model.IOModel;

/**
 * IO/CPU cost follows the OPERATIONS in the current work unit; lock cost follows its distinct
 * RESOURCES.
 *
 * <p>The property that matters most here is invariance: splitting a transaction into batches must
 * not change how much work it does in total. Interleaving changes *when* locks are taken, not *how
 * much* a transaction executes, and the ladder's hold-time criterion divides by the transaction's
 * span - so a cost basis that inflated with batch count would move the denominator and read as a
 * hold-time result.
 */
class BatchCostBasisTest {

    private static final List<Model> POOL = new ModelFactory(200).createModels(List.of(1));

    private static Resource res(int instance) {
        return new Resource(instance, POOL.get(0).properties().get(0));
    }

    private static IOModel ioModel() {
        return new IOModel(SimulatorConfig.fromParameters(SimulatorParameters.builder()
                .totalModels(1).propertiesPerModel(List.of(1)).semanticOverlap(0.0)
                .instancePoolSize(200).totalLocks(200).maxTransactionModels(1)
                .ioTimePerResource(1).cpuTimePerResource(1)
                .lockIOTimePerResource(1).lockCPUTimePerResource(1)
                .build(), POOL));
    }

    private static Transaction txn(List<Operation> ops) {
        List<Resource> resources = new ArrayList<>(
                ops.stream().map(Operation::getResource).distinct().toList());
        Transaction t = new Transaction("T1", 0L, resources, new TransactionGroup(), -1, ops, null);
        t.initializeState();
        return t;
    }

    /**
     * Claim a batch and signal its grant, which is what the coordinator does.
     *
     * <p>Both halves are required: the cursor advances on a claim but only commits on a grant, so a
     * bare {@code takeNextBatch} loop re-serves the same batch forever. That is the contract that
     * keeps a DENIED batch from being skipped, and driving the cursor without it does not model
     * acquisition - it models an acquisition that never succeeds.
     */
    private static void acquireBatch(Transaction t, int batchSize) {
        t.takeNextBatch(batchSize);
        t.recordBatchAcquired(t.getCurrentBatchDistinctResourceCount(), 0L);
    }

    private static Transaction writerOver(int count) {
        List<Operation> ops = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            ops.add(new Operation(OperationType.WRITE, res(i)));
        }
        return txn(ops);
    }

    @Test
    void withoutACursorCostCoversTheWholeTransaction() {
        Transaction t = writerOver(8);
        assertEquals(8, ioModel().getIOTime(t));
        assertEquals(8, ioModel().getCPUTime(t));
    }

    @Test
    void withACursorCostCoversOnlyTheBatchInFlight() {
        Transaction t = writerOver(8);
        acquireBatch(t, 3);
        assertEquals(3, ioModel().getIOTime(t), "one batch of three costs three, not eight");
        acquireBatch(t, 3);
        assertEquals(3, ioModel().getCPUTime(t));
        acquireBatch(t, 3);
        assertEquals(2, ioModel().getCPUTime(t), "the final short batch costs what it contains");
    }

    /**
     * Step 7b's property, at unit level: total work is the same however the transaction is split.
     * The two-run version compares whole simulations; this catches an off-by-one in the split
     * without waiting for the ladder.
     */
    @Test
    void totalWorkIsInvariantToBatchSize() {
        int k = 12;
        long whole = ioModel().getIOTime(writerOver(k)) + ioModel().getCPUTime(writerOver(k));

        for (int batchSize : new int[] {1, 2, 3, 5, 7, 12, 20}) {
            Transaction t = writerOver(k);
            long summed = 0;
            while (t.getOperationCursor() < k) {
                acquireBatch(t, batchSize);
                summed += ioModel().getIOTime(t) + ioModel().getCPUTime(t);
            }
            assertEquals(whole, summed,
                    "batches of " + batchSize + " must sum to the same total work as one whole "
                            + "acquisition - interleaving changes WHEN locks are taken, not HOW MUCH "
                            + "work is done");
        }
    }

    /**
     * IO/CPU are per operation, so a repeat touch costs twice; lock acquisition is per distinct
     * resource, so it does not. This is the divergence that only appears in program mode - under
     * RANDOM_OPERATIONS synthesis emits one operation per resource and the two bases coincide.
     */
    @Test
    void aRepeatTouchCostsTwiceForWorkButOnceForLocking() {
        // The Deposit shape: R x, W x - two operations over one resource.
        Transaction deposit = txn(List.of(
                new Operation(OperationType.READ, res(1)),
                new Operation(OperationType.WRITE, res(1))));

        assertEquals(2, ioModel().getIOTime(deposit), "two operations, two units of work");
        assertEquals(1, deposit.getCurrentBatchDistinctResourceCount(),
                "but one resource, so one lock - a repeat touch of a held lock is a no-op");
    }

    /** Under RANDOM_OPERATIONS shapes the operation and resource bases agree exactly, which is why
     * moving to the operation basis leaves the ladder undisturbed. */
    @Test
    void oneOperationPerResourceMakesTheTwoBasesAgree() {
        Transaction t = writerOver(25);
        assertEquals(t.getResources().size(), t.getCurrentBatchOperationCount());
        assertEquals(25, ioModel().getIOTime(t));
    }

    /** Operation-less fixtures still cost something sensible rather than zero. */
    @Test
    void anOperationLessTransactionFallsBackToItsResourceCount() {
        Transaction t = new Transaction("T1", 0L, List.of(res(1), res(2), res(3)),
                new TransactionGroup(), -1);
        t.initializeState();
        assertEquals(3, ioModel().getIOTime(t));
    }
}
