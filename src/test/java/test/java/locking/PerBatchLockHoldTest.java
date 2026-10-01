package test.java.locking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import de.tum.tssimulator.core.Model;
import de.tum.tssimulator.core.ModelFactory;
import de.tum.tssimulator.core.Resource;
import de.tum.tssimulator.core.Transaction;
import de.tum.tssimulator.core.TransactionGroup;
import de.tum.tssimulator.core.program.Operation;
import de.tum.tssimulator.core.program.OperationType;
import de.tum.tssimulator.monitoring.SimulationMetrics;

/**
 * Lock hold is measured from each batch's OWN acquisition to the SINGLE release - never as
 * per-batch duration.
 *
 * <p>This is the highest-value assertion in the operation-level work, because the wrong
 * implementation fails in the same DIRECTION as the right one. The ladder's hold-time criterion
 * expects mean hold per lock unit to be about half a transaction's span; batch-duration accounting
 * would report roughly a k-th of it. Both are "hold time went down". Only the magnitude
 * distinguishes them, so a spectacular pass on that criterion is evidence of this bug rather than
 * of success.
 *
 * <p>Record COUNT is asserted separately from durations throughout. "k batches produced k records"
 * and "the durations are right" fail for entirely different reasons, and an aggregate cannot tell
 * them apart - a lesson bought the hard way when a wrong SUM sent three rounds of hypotheses at
 * hash collisions and lock granularity, while a record-count assertion named the real defect on its
 * first run.
 */
class PerBatchLockHoldTest {

    private static final List<Model> POOL = new ModelFactory(200).createModels(List.of(1));

    private static Resource res(int instance) {
        return new Resource(instance, POOL.get(0).properties().get(0));
    }

    private static Transaction txnWith(int operationCount) {
        List<Operation> ops = new ArrayList<>();
        List<Resource> resources = new ArrayList<>();
        for (int i = 1; i <= operationCount; i++) {
            ops.add(new Operation(OperationType.WRITE, res(i)));
            resources.add(res(i));
        }
        Transaction t = new Transaction("T1", 0L, resources, new TransactionGroup(), -1, ops, null);
        t.initializeState();
        return t;
    }

    @Test
    void oneBatchPerAcquisitionIsRetainedUntilRelease() {
        Transaction t = txnWith(4);
        for (int i = 0; i < 4; i++) {
            t.recordBatchAcquired(1, i * 10L);
        }

        assertEquals(4, t.getHeldBatches().size(), "every batch is retained, not overwritten");
        assertEquals(0L, t.getLockHoldStart(), "first acquisition, not the latest");
        assertEquals(4, t.getLockCountHeld(), "total across batches");
    }

    /**
     * The core assertion: durations must be the DESCENDING sequence release - acquireMs_i, not
     * the per-batch execution times. With batches acquired at 0/10/20/30 and release at 100 the
     * right answer is 100/90/80/70; batch-duration accounting would give 10/10/10/<something>.
     */
    @Test
    void durationsRunFromEachAcquisitionToTheSingleRelease() {
        SimulationMetrics metrics = new SimulationMetrics(200, 16);
        Transaction t = txnWith(4);
        long[] acquiredAt = {0L, 10L, 20L, 30L};
        for (long at : acquiredAt) {
            t.recordBatchAcquired(1, at);
        }

        long release = 100L;
        for (Transaction.HeldBatch batch : t.getHeldBatches()) {
            metrics.recordLockHold(batch.lockCount(), release - batch.acquire(), false);
        }

        // Count first: a wrong count and wrong durations are different defects.
        assertEquals(4, metrics.getLockHoldRecordingCount(), "k batches must emit k records");
        assertEquals(4.0, metrics.getTotalLocksAcquiredCount(), 1e-9);

        // 100 + 90 + 80 + 70 = 340 lock-ms over 4 lock units.
        assertEquals(85.0, metrics.getMeanLockHoldDurationPerLock(), 1e-9,
                "mean hold must be ~half the span (85 of 100), NOT the per-batch execution time");
    }

    /**
     * The discriminator stated as a ratio, exactly as the ladder computes it: mean hold per lock
     * unit over the transaction's own span. Correct accounting gives ~0.5; batch-duration
     * accounting gives ~1/k. At k=10 that is 0.55 against 0.1 - no run-to-run noise closes it.
     */
    @Test
    void theRatioSeparatesCorrectAccountingFromBatchDurationByAnOrderOfMagnitude() {
        int k = 10;
        long span = 100L;
        Transaction t = txnWith(k);
        for (int i = 0; i < k; i++) {
            t.recordBatchAcquired(1, i * (span / k));
        }

        SimulationMetrics correct = new SimulationMetrics(200, 16);
        for (Transaction.HeldBatch batch : t.getHeldBatches()) {
            correct.recordLockHold(batch.lockCount(), span - batch.acquire(), false);
        }

        SimulationMetrics batchDuration = new SimulationMetrics(200, 16);
        for (int i = 0; i < k; i++) {
            batchDuration.recordLockHold(1, span / k, false); // the WRONG reading
        }

        double correctRatio = correct.getMeanLockHoldDurationPerLock() / span;
        double wrongRatio = batchDuration.getMeanLockHoldDurationPerLock() / span;

        assertEquals(0.55, correctRatio, 0.01, "hold spans average about half the transaction");
        assertEquals(0.10, wrongRatio, 0.01, "batch-duration accounting would report about 1/k");
        assertTrue(correctRatio > 4 * wrongRatio,
                "the two readings must be far enough apart that noise cannot confuse them");
    }

    @Test
    void wholeFootprintAcquisitionProducesExactlyOneBatch() {
        Transaction t = txnWith(8);
        t.recordBatchAcquired(8, 5L);

        assertEquals(1, t.getHeldBatches().size(),
                "at operationBatchSize=0 there is one acquisition and so one record - identical to "
                        + "the single span the scalar pair used to hold");
        assertEquals(8, t.getLockCountHeld());
        assertEquals(5L, t.getLockHoldStart());
    }

    @Test
    void clearingTrackingDiscardsEveryBatch() {
        Transaction t = txnWith(3);
        t.recordBatchAcquired(1, 0L);
        t.recordBatchAcquired(1, 10L);
        t.clearLockHoldTracking();

        assertEquals(0, t.getHeldBatches().size());
        assertEquals(-1L, t.getLockHoldStart(), "untracked again");
        assertEquals(0, t.getLockCountHeld());
    }

    // --- cursor-at-abort histogram -------------------------------------------------------

    @Test
    void abortProgressIsADistributionNotAScalar() {
        SimulationMetrics metrics = new SimulationMetrics(200, 16);
        metrics.recordCursorProgressAtAbort(0.0);
        metrics.recordCursorProgressAtAbort(0.35);
        metrics.recordCursorProgressAtAbort(0.9);

        long[] buckets = metrics.getCursorProgressAtAbortBuckets();
        assertEquals(3, metrics.getCursorProgressAtAbortSamples());
        assertEquals(1, buckets[0]);
        assertEquals(1, buckets[3]);
        assertEquals(1, buckets[9]);
        assertTrue(metrics.isCursorProgressAtAbortNonDegenerate());
    }

    /**
     * Degeneracy is the failure mode worth naming: without interleaving every abort discards
     * nothing, so all progress lands in one bucket. If that happens WITH interleaving on, the
     * cursor is not doing its job and any hold-time improvement is coming from somewhere else.
     */
    @Test
    void allAbortsInOneBucketIsReportedAsDegenerate() {
        SimulationMetrics metrics = new SimulationMetrics(200, 16);
        for (int i = 0; i < 50; i++) {
            metrics.recordCursorProgressAtAbort(0.0);
        }
        assertTrue(metrics.getCursorProgressAtAbortSamples() > 0);
        assertTrue(!metrics.isCursorProgressAtAbortNonDegenerate(),
                "a spike at 0 means no transaction ever aborted mid-cursor");
    }

    @Test
    void progressIsClampedIntoTheHistogramRange() {
        SimulationMetrics metrics = new SimulationMetrics(200, 16);
        metrics.recordCursorProgressAtAbort(1.0);
        metrics.recordCursorProgressAtAbort(-0.5);
        long[] buckets = metrics.getCursorProgressAtAbortBuckets();
        assertEquals(1, buckets[9], "1.0 belongs in the top bucket, not out of bounds");
        assertEquals(1, buckets[0]);
    }
}
