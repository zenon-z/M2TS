package de.tum.tssimulator.core;

import java.util.Arrays;

/**
 * Manages aggregate metrics across all transactions in the simulation.
 * 
 * This class has a single responsibility: track and report global statistics
 * for completed transactions. Separated from Transaction class to follow SRP.
 *
 * <p><b>READ THIS INSIDE THE RUN, NOT AFTER IT.</b> Every field here is static and process-wide, and
 * {@code SeedSweepRunner} calls {@link #reset()} in a {@code finally} block after each seed (four
 * such sites). Anything that runs a sweep and then reads these getters sees ZEROS - silently, with
 * no error and no partial value to hint at the problem. That cost one full diagnostic cycle: five
 * unit tests passed while the real cell reported count=0 against an expected ~570, and it was caught
 * only because the expected count had been written down before the run.
 *
 * <p>The supported way to get these numbers out of a sweep is
 * {@code ExperimentRuntimeSnapshotFactory}, which harvests them at snapshot capture - inside the
 * run, before the reset. It is the ONLY reader downstream of a sweep; every
 * other caller constructs a Simulator directly and reads within its own scope, where the
 * constructor-time reset is the only one that fires. If you add a getter here, add its harvest
 * there too.
 */
public class TransactionAggregateMetrics {
    private static long totalSimulatedIO = 0;
    private static long totalSimulatedCPU = 0;
    private static long completedTransactions = 0;

    /**
     * Per-attempt BACKOFF intervals, recorded AT CLOSE rather than at completion.
     *
     * <p>Why this cannot be a completion-keyed metric. {@code Transaction.cumulativeBackoff} is a
     * whole-LINEAGE total, carried forward onto each restart by
     * {@code copyLineageTimeAccountingFrom}. A PER-ATTEMPT reading needs the interval attributed to
     * the object that actually waited it - and that object is usually not the one that completes:
     * on a cascading cell the group is completed by a later member of the lineage, whose own
     * per-attempt backoff is legitimately zero. Every completion-keyed and abort-keyed hook tried
     * on this therefore read zero on 481 aborted plus 416 completed attempts while the lineage
     * accumulator simultaneously reported ~6.05bn of backoff - a contradiction that took two
     * reversed conclusions to resolve.
     *
     * <p>Recording where the interval CLOSES removes the problem at the source: the observation is
     * made while the owning object still holds it, so nothing downstream can zero, inherit, or
     * outlive it. {@code intervalCount} is asserted separately from {@code intervalTotal} because
     * "were all the intervals seen" and "were they the right length" fail for different reasons and
     * one aggregate cannot distinguish them.
     */
    private static long backoffIntervalCount = 0;
    private static long backoffIntervalTotal = 0;
    private static final int RETRY_HISTOGRAM_BUCKETS = 13;
    private static final long[] backoffRetryHistogram = new long[RETRY_HISTOGRAM_BUCKETS];

    // Advanced Metrics
    private static long totalUsefulWork = 0;
    private static long totalWastedWork = 0;
    private static long missedDeadlineCount = 0;

    /**
     * Record completion of a transaction and add its metrics to aggregates.
     */
    public static void recordCompletion(TransactionMetrics metrics, long totalTime, boolean missedDeadline) {
        totalSimulatedIO += metrics.getTime(ExecutionPhase.IO);
        totalSimulatedCPU += metrics.getTime(ExecutionPhase.CPU);
        completedTransactions++;
        
        totalUsefulWork += totalTime;
        if (missedDeadline) {
            missedDeadlineCount++;
        }
    }

    /**
     * Record one closed BACKOFF interval, at the moment it closes.
     *
     * @param elapsed    the interval's duration; callers must not pass a negative value
     * @param retryDepth the lineage's retry count when the interval closed, bucketed into the
     *                   histogram (the last bucket is a catch-all for the backoff ladder's cap)
     */
    public static void recordBackoffInterval(long elapsed, int retryDepth) {
        backoffIntervalCount++;
        backoffIntervalTotal += elapsed;
        int bucket = Math.min(Math.max(retryDepth, 0), RETRY_HISTOGRAM_BUCKETS - 1);
        backoffRetryHistogram[bucket]++;
    }

    /** How many BACKOFF intervals were observed. Assert this BEFORE reading any total. */
    public static long getBackoffIntervalCount() {
        return backoffIntervalCount;
    }

    /**
     * Summed duration of every observed BACKOFF interval, over ALL lineages - completed, aborted
     * and still-open alike. Reconciles against the sum of {@code cumulativeBackoff} across
     * lineages; do NOT divide it by a count of COMPLETED lineages, which is a different (censored)
     * population - that error has been made twice on this metric already.
     */
    public static long getBackoffIntervalTotal() {
        return backoffIntervalTotal;
    }

    /** Retry-depth distribution of the observed intervals; index is retry count, last is a cap. */
    public static long[] getBackoffRetryHistogram() {
        return backoffRetryHistogram.clone();
    }

    /**
     * Record an aborted transaction's wasted work.
     */
    public static void recordAbort(long wastedTime) {
        totalWastedWork += wastedTime;
    }

    public static long getTotalSimulatedIO() {
        return totalSimulatedIO;
    }

    public static long getTotalSimulatedCPU() {
        return totalSimulatedCPU;
    }

    public static long getCompletedTransactions() {
        return completedTransactions;
    }

    public static long getTotalUsefulWork() {
        return totalUsefulWork;
    }

    public static long getTotalWastedWork() {
        return totalWastedWork;
    }

    /** Live count of PARENT transactions in each LineagePhase - the population whose integral
     * over the measurement window is the time-breakdown figure's data.
     *
     * <p>Here rather than on Transaction so the existing {@link #reset()} covers it. A
     * process-global counter that no per-run reset touches contaminates every run after the first
     * in a JVM with the previous run's residue - and because that residue is deterministic it
     * reads as an ordinary between-cell difference rather than as contamination.
     *
     * <p>PARENT-ONLY, matching the guard at every markPhase call site. The MPL-population gate
     * assumes exactly that population; mixing in bookkeepers is the same numerator/denominator
     * mismatch that once let frac_waiting_room exceed 1.0. */
    // SIZED FROM THE ENUM, not a literal. This was new int[5] when LineagePhase had five
    // constants; adding BLOCKED made the literal silently one short, and enterPhase(5) would
    // have thrown ArrayIndexOutOfBounds at the first block rather than miscounting - loud, but
    // only at runtime. SimulationMetrics.phaseOccupancy already sized itself this way.
    private static final int[] phasePopulation =
            new int[Transaction.LineagePhase.values().length];

    public static void enterPhase(int ordinal) {
        phasePopulation[ordinal]++;
    }

    public static void exitPhase(int ordinal) {
        phasePopulation[ordinal]--;
    }

    public static int[] phasePopulation() {
        return phasePopulation.clone();
    }

    public static int phasePopulationTotal() {
        int t = 0;
        for (int c : phasePopulation) {
            t += c;
        }
        return t;
    }

    /** Verification item 16a's pattern applied here: assert the reset HAPPENED rather than
     * trusting the convention. A nonzero count at run start means the previous run in this JVM
     * leaked a transaction out of the population without exiting its phase. */
    public static void assertPhasePopulationClear() {
        int t = phasePopulationTotal();
        if (t != 0) {
            throw new IllegalStateException(
                    "phase population not reset at run start: " + t + " still counted "
                    + Arrays.toString(phasePopulation));
        }
    }

    public static long getMissedDeadlineCount() {
        return missedDeadlineCount;
    }

    /**
     * Reset all aggregate metrics. Called at simulation start.
     */
    public static void reset() {
        totalSimulatedIO = 0;
        totalSimulatedCPU = 0;
        completedTransactions = 0;
        totalUsefulWork = 0;
        totalWastedWork = 0;
        missedDeadlineCount = 0;
        backoffIntervalCount = 0;
        backoffIntervalTotal = 0;
        Arrays.fill(backoffRetryHistogram, 0L);
        Arrays.fill(phasePopulation, 0);
    }

    private TransactionAggregateMetrics() {
        // Static utility class - prevent instantiation
    }
}
