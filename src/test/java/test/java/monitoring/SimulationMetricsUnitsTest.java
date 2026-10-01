package test.java.monitoring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;

import org.junit.jupiter.api.Test;

import de.tum.tssimulator.bootstrap.ExperimentRuntimeSnapshot;
import de.tum.tssimulator.config.SimulatorParameters;
import de.tum.tssimulator.core.Transaction;
import de.tum.tssimulator.experiments.SimulationRunner;

/**
 * Unit-conversion gate for the windowed metrics.
 *
 * <p><b>The defect this exists for.</b>
 * {@code getMeasurementWindowS}, {@code getWarmupDiscardedS} and
 * {@code getThroughputTransactionsPerSecond} divided virtual time by 1000. Virtual time is in
 * TICKS and a tick is a MICROSECOND, so all three returned milliseconds while being named,
 * documented and consumed as seconds - and the {@code throughput} column of every raw CSV was
 * 1000x too small.
 *
 * <p><b>Why it survived, which is the part worth guarding against.</b> It was internally
 * CONSISTENT. {@code getWindowedThroughput()} divides completions by the same wrong window, so
 * the identity {@code throughput x window == completions} held perfectly; the sibling
 * {@code getThroughputTransactionsPerSecond()} carried the identical error, so the two agreed;
 * and every cross-run comparison was expressed in the same wrong unit, so no figure ever looked
 * odd. Nothing internal to the metric could see it.
 *
 * <p>So the identity test below is necessary but NOT sufficient, and both are here deliberately:
 *
 * <ul>
 *   <li>{@link #throughputTimesWindowEqualsCompletions()} - the internal identity. Catches a
 *       conversion applied to one accessor and not its partner.</li>
 *   <li>{@link #windowMatchesTheConfiguredHorizonInSeconds()} - the EXTERNAL anchor, which is what
 *       the original defect actually needed. It compares the reported window against the
 *       configured {@code simulationDurationSeconds - warmupSeconds}, a quantity the metric code
 *       cannot influence. A consistent 1000x error fails here and nowhere else.</li>
 * </ul>
 */
class SimulationMetricsUnitsTest {

    private static final int DURATION_S = 20;
    private static final int WARMUP_S = 5;

    private static ExperimentRuntimeSnapshot runOnce() {
        int models = 4;
        int props = 5;
        int pool = 200;
        int d = models * props * pool;
        SimulatorParameters params = SimulatorParameters.builder()
                .totalModels(models)
                .propertiesPerModel(Collections.nCopies(models, props))
                .instancePoolSize(pool)
                .totalResources(d)
                .totalLocks(d)
                .semanticOverlap(0.0)
                .useConstantTransactionSize(true)
                .constantTransactionSize(10)
                .maxTransactionModels(2)
                .useRandomResourceSelection(true)
                .ioTimePerResource(1000)
                .cpuTimePerResource(1000)
                .lockIOTimePerResource(1)
                .lockCPUTimePerResource(1)
                .numberOfIoProcessors(4)
                .numberOfCpuProcessors(4)
                .initialTransactions(4)
                .stepTimeSlice(10L)
                .simulationDurationSeconds(DURATION_S)
                .warmupSeconds(WARMUP_S)
                .transactionSamplingSeed(1000003L)
                .semanticOverlapSeed(1000003L)
                .build();
        try {
            return new SimulationRunner().runDetailedAllowPartial("units_probe", params).snapshot();
        } finally {
            Transaction.resetMetrics();
        }
    }

    /**
     * The internal identity. Necessary, and on its own it would have passed throughout the
     * 1000x era - see this class's javadoc.
     */
    @Test
    void throughputTimesWindowEqualsCompletions() {
        ExperimentRuntimeSnapshot s = runOnce();
        double reconstructed = s.windowedThroughput() * s.measurementWindowS();
        assertEquals(s.completionsInWindow(), reconstructed, Math.max(1.0, reconstructed * 1e-6),
                "windowedThroughput() x measurementWindowS() must reconstruct completionsInWindow(); "
                        + "a mismatch means one of the two carries a conversion the other does not");
    }

    /**
     * The external anchor, and the one that actually catches a consistent conversion error.
     *
     * <p>{@code simulationDurationSeconds} and {@code warmupSeconds} are inputs the metric code
     * cannot influence, so a window expressed in the wrong unit cannot agree with them. Under the
     * old {@code /1000.0} this reported 15000 for a 15-second window.
     */
    @Test
    void windowMatchesTheConfiguredHorizonInSeconds() {
        ExperimentRuntimeSnapshot s = runOnce();
        double expectedWindowS = DURATION_S - WARMUP_S;

        assertTrue(Math.abs(s.measurementWindowS() - expectedWindowS) <= 1.0,
                "measurementWindowS()=" + s.measurementWindowS() + " must be ~"
                        + expectedWindowS + " SECONDS for a " + DURATION_S + "s run with a "
                        + WARMUP_S + "s warm-up. A value near " + (expectedWindowS * 1000)
                        + " means virtual time is being divided by 1000 (milliseconds) rather than "
                        + "by TICKS_PER_SECOND = 1_000_000.");

        assertTrue(Math.abs(s.warmupDiscardedS() - WARMUP_S) <= 1.0,
                "warmupDiscardedS()=" + s.warmupDiscardedS() + " must be ~" + WARMUP_S
                        + " SECONDS; same conversion, same failure mode.");
    }

    /**
     * An absolute plausibility bound on throughput, anchored to service demand rather than to any
     * other metric. With k=10 resources at (io+cpu+locks) = 2002 ticks each, one transaction
     * demands ~20ms, so 4 processors cannot exceed ~200/s and - with real contention - will not
     * fall below a small fraction of it. The old 1000x error put this at ~0.2/s.
     */
    @Test
    void throughputIsPlausibleAgainstServiceDemand() {
        ExperimentRuntimeSnapshot s = runOnce();
        double serviceSecondsPerTxn = 10 * (1000 + 1000 + 1 + 1) / 1_000_000.0; // ~0.02s
        double idealTps = 4 / serviceSecondsPerTxn;                             // ~200/s

        assertTrue(s.windowedThroughput() > idealTps * 0.01,
                "throughput " + s.windowedThroughput() + "/s is implausibly far below the ideal "
                        + idealTps + "/s implied by service demand - a 1000x unit error looks "
                        + "exactly like this");
        assertTrue(s.windowedThroughput() <= idealTps * 1.05,
                "throughput " + s.windowedThroughput() + "/s exceeds the ideal " + idealTps
                        + "/s implied by service demand, which is impossible without a unit error");
    }
}
