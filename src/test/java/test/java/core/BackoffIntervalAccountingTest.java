package test.java.core;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import de.tum.tssimulator.core.Transaction;
import de.tum.tssimulator.core.TransactionAggregateMetrics;

/**
 * Per-attempt BACKOFF must be observed where the interval CLOSES, not where a transaction completes.
 *
 * <p>The defect this pins: {@code cumulativeBackoff} is a whole-lineage total carried forward onto
 * every restart, so an attempt's own backoff is only readable from the object that waited it - and
 * on a cascading cell the object that COMPLETES a group is usually a later member whose own backoff
 * is legitimately zero. Completion-keyed and abort-keyed hooks both read zero across 481 + 416
 * observations while the lineage accumulator reported ~6.05bn, and two successive conclusions about
 * that contradiction had to be reversed before the cause was located.
 */
public class BackoffIntervalAccountingTest {

    @BeforeEach
    public void resetMetrics() {
        Transaction.resetMetrics();
    }

    private static Transaction txn(String id) {
        Transaction t = new Transaction(id, List.of(), null);
        t.initializeState();
        return t;
    }

    @Test
    public void aClosedBackoffIntervalIsObservedWithItsDuration() {
        Transaction t = txn("A");
        t.markPhase(Transaction.LineagePhase.BACKOFF, 1_000L);
        t.markPhase(Transaction.LineagePhase.ADMISSION_WAIT, 4_500L);

        // Count and total asserted separately: "was the interval seen at all" and "was it the right
        // length" fail for different reasons, and one aggregate cannot tell them apart.
        Assertions.assertEquals(1L, TransactionAggregateMetrics.getBackoffIntervalCount(),
                "exactly one BACKOFF interval closed");
        Assertions.assertEquals(3_500L, TransactionAggregateMetrics.getBackoffIntervalTotal(),
                "interval duration is close-time minus open-time");
    }

    @Test
    public void theIntervalSurvivesTheObjectThatWaitedItNeverCompleting() {
        // THE regression. The waiting object is abandoned at restart and never completes; a
        // successor inherits the lineage total and completes instead. A completion-keyed hook reads
        // the successor's own backoff, which is correctly zero - and so reports nothing.
        Transaction attempt1 = txn("A-1");
        attempt1.markPhase(Transaction.LineagePhase.BACKOFF, 1_000L);

        Transaction attempt2 = txn("A-2");
        attempt2.copyLineageTimeAccountingFrom(attempt1, 9_000L); // closes attempt1's interval
        attempt2.markPhase(Transaction.LineagePhase.RUNNING, 9_000L);
        attempt2.finalizeLineageTimeAccounting(11_000L);          // attempt2 is what completes

        Assertions.assertEquals(1L, TransactionAggregateMetrics.getBackoffIntervalCount(),
                "the abandoned attempt's interval must still be observed");
        Assertions.assertEquals(8_000L, TransactionAggregateMetrics.getBackoffIntervalTotal());
        Assertions.assertEquals(0L, attempt2.getCumulativeRunning() - 2_000L,
                "sanity: the successor's own RUNNING interval is its own");
    }

    @Test
    public void theObservedTotalReconcilesWithTheLineageAccumulator() {
        // Three backoff intervals across a two-restart lineage. The lineage total and the sum of
        // observations are built by completely different mechanisms and must agree.
        Transaction a = txn("A-1");
        a.markPhase(Transaction.LineagePhase.BACKOFF, 0L);
        Transaction b = txn("A-2");
        b.copyLineageTimeAccountingFrom(a, 500L);
        b.markPhase(Transaction.LineagePhase.BACKOFF, 500L);
        Transaction c = txn("A-3");
        c.copyLineageTimeAccountingFrom(b, 1_700L);
        c.markPhase(Transaction.LineagePhase.BACKOFF, 1_700L);
        c.finalizeLineageTimeAccounting(5_000L);

        Assertions.assertEquals(3L, TransactionAggregateMetrics.getBackoffIntervalCount());
        Assertions.assertEquals(c.getCumulativeBackoff(),
                TransactionAggregateMetrics.getBackoffIntervalTotal(),
                "summed observations must equal the lineage accumulator (500 + 1200 + 3300)");
        Assertions.assertEquals(5_000L, TransactionAggregateMetrics.getBackoffIntervalTotal());
    }

    @Test
    public void nonBackoffPhasesAndUnopenedClosesAreNotObserved() {
        Transaction t = txn("A");
        t.finalizeLineageTimeAccounting(500L);        // nothing open - must be a no-op
        t.markPhase(Transaction.LineagePhase.RUNNING, 1_000L);
        t.markPhase(Transaction.LineagePhase.PENDING_QUEUE, 2_000L);
        t.finalizeLineageTimeAccounting(3_000L);

        Assertions.assertEquals(0L, TransactionAggregateMetrics.getBackoffIntervalCount(),
                "only BACKOFF intervals are observed, and only when one was actually open");
        Assertions.assertEquals(0L, TransactionAggregateMetrics.getBackoffIntervalTotal());
    }

    @Test
    public void retryDepthIsBucketedPerInterval() {
        Transaction t = txn("A");
        t.markPhase(Transaction.LineagePhase.BACKOFF, 0L);
        t.markPhase(Transaction.LineagePhase.RUNNING, 100L);

        long[] hist = TransactionAggregateMetrics.getBackoffRetryHistogram();
        Assertions.assertEquals(1L, hist[0], "a lineage at retry 0 lands in bucket 0");
        Assertions.assertEquals(1L, Arrays.stream(hist).sum(),
                "histogram total must equal the interval count");
    }
}
