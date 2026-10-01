package de.tum.tssimulator.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import de.tum.tssimulator.bookkeeping.CprTopologyMode;
import de.tum.tssimulator.config.ConcurrencyModel;
import de.tum.tssimulator.config.SimulatorParameters;
import de.tum.tssimulator.config.TransactionGenerationMode;
import de.tum.tssimulator.experiments.SimulationRunner.SimulationRunOutcome;
import de.tum.tssimulator.experiments.support.SeedSweepRunner;
import de.tum.tssimulator.experiments.support.TerminatedBy;

/**
 * Regression guard for the reentrant-abort dropped-reference bug found via targeted replay
 * (see VictimTraceLog). Root cause: TransactionGroup.abortAll()/blockAll() called
 * clearFromProcessors() unconditionally, and three TransactionLockCoordinator call sites
 * (abortHardDeniedGroup, blockEntireTransactionGroup, abortWaitersBlockedByGroup) guarded only
 * against TransactionGroupState.ABORTED and never against ABORTING - the intermediate state was
 * added without propagating the check to those three sites. A group's locks
 * aren't released until its rollback finishes, so another transaction waiting on one of those
 * locks could re-trigger abortAll() on the SAME still-ABORTING group, silently evicting whichever
 * member was `current` in a processor at that moment (e.g. mid-ROLLBACK-phase tick) with no
 * re-enqueue and no state transition - a permanent orphan: RUNNING forever, in no queue, in no
 * processor. This is what caused the huge per-seed variance in the dynamic-CPR-arm throughput
 * figure: a small number of these per run, accumulating over time, degrading into total collapse.
 *
 * seed=1000003 (this class's TOPOLOGY_SEED and simulation seed) is a known, deterministic,
 * byte-identically-reproducible instance: pre-fix it always trips the watchdog at vt=243800ms
 * with T20-RO (a maxBkDepth=3 parent) permanently stuck RUNNING/phase=ROLLBACK and its bookkeeper
 * BK0-BK0-BK0-T20-RO stuck RUNNING/phase=LOCK. Post-fix the same seed runs to TIME_LIMIT with
 * sustained throughput.
 */
class TransactionGroupReentrantAbortRegressionTest {

    private static final long TOPOLOGY_SEED = 1000003L;
    private static final long RUN_SEED = 1000003L;

    // Long enough to safely pass the pre-fix collapse point (watchdog tripped at vt=243800ms on
    // unmodified trunk) with margin, short enough to stay a fast `mvn test` citizen.
    private static final int DURATION_S = 500;

    /**
     * Floor separating "recovered" from "collapsed". NOT a performance target.
     *
     * <p>DERIVATION, measured by {@link #calibrateHealthyThroughputFloor()} on the current cost
     * basis, 5 seeds, {@code DURATION_S}=500, all reaching TIME_LIMIT:
     *
     * <pre>
     *   seed 1000003  0.6620/s   &lt;- the seed THIS test guards, and the MINIMUM of the five
     *   seed 1000019  2.0760/s
     *   seed 1000037  0.7920/s
     *   seed 1000081  1.4300/s
     *   seed 1000099  4.4580/s
     *   min 0.6620  mean 1.8836  max 4.4580  sd 1.5455  (CV 82%)
     * </pre>
     *
     * <p>The floor is set at <b>one order of magnitude above the pre-fix collapse value</b>
     * (0.0075/s x 10), which also lands ~9x below the slowest healthy seed observed -- i.e. close
     * to the geometric midpoint of the two scales, with large margin in both directions. It is
     * deliberately NOT set just under 0.6620: healthy throughput on this config has CV 82%, so a
     * floor near the observed minimum would flake on ordinary seed variance.
     *
     * <p>WHY THE PREVIOUS FLOOR OF 1.0 FAILED, which is not a regression. The orphan bug is not
     * back: the two DIRECT signatures above (watchdog, ORPHAN_TRANSACTIONS) both pass, and an
     * orphan is an orphan regardless of timing. 1.0 was calibrated against a cost model that has
     * since changed five times, every one of them reducing throughput -- rollback priced at the
     * work it undoes (9959f0e, and this is an ABORT test), group members serialised across
     * IO union CPU (5c4b453), the forward-transition gain removed (f8fa942), lock service cut
     * 1000x (15e5296), and the ms->us time base (4194a59). It also sat INSIDE the healthy range
     * measured above, so it would pass on three of those five seeds and fail on two.
     *
     * <p>PROVISIONAL, and proportionate. Five seeds is a spread, not a converged estimate -- no
     * variance characterisation was run for this workload. That is the right weight for a
     * regression-test floor and would not be enough for a published number.
     */
    private static final double HEALTHY_FLOOR = 0.075;


    private ByteArrayOutputStream capturedOut;
    private PrintStream originalOut;

    @AfterEach
    void restoreStdout() {
        if (originalOut != null) {
            System.setOut(originalOut);
        }
        System.clearProperty("ts.diag.watchdogStateDump");
        Transaction.resetMetrics();
    }

    @Test
    void abortingGroupSurvivesAConcurrentReentrantAbortWithoutDroppingATransaction() {
        // OrphanTransactionAudit only prints when this diagnostic flag is on (DeadlockStateDumper
        // fires it at watchdog trip AND at normal run end) - capture stdout to assert on it
        // rather than depending on SimulationRunner exposing SimulatorContext.
        System.setProperty("ts.diag.watchdogStateDump", "true");
        capturedOut = new ByteArrayOutputStream();
        originalOut = System.out;
        System.setOut(new PrintStream(capturedOut, true, StandardCharsets.UTF_8));

        SimulatorParameters params = SeedSweepRunner.copyBuilder(configDynamicK50())
                .initialTransactions(16)
                .simulationDurationSeconds(DURATION_S)
                .build();

        SeedSweepRunner sweepRunner = new SeedSweepRunner();
        List<SimulationRunOutcome> outcomes = sweepRunner.runRawWithCensoring(
                "reentrantAbortRegression", params, new long[] { RUN_SEED });
        SimulationRunOutcome outcome = outcomes.get(0);

        System.setOut(originalOut);
        String output = capturedOut.toString(StandardCharsets.UTF_8);

        // Pre-fix signature #1: the run permanently stalls and never reaches TIME_LIMIT.
        assertTrue(outcome.terminatedBy() != TerminatedBy.WATCHDOG,
                "seed=" + RUN_SEED + " tripped the watchdog again - the reentrant-abort dropped-"
                        + "reference bug (TransactionGroup.clearFromProcessors() re-entrant on an "
                        + "ABORTING group) appears to have regressed. terminatedBy="
                        + outcome.terminatedBy());

        // Pre-fix signature #2: OrphanTransactionAudit finds a RUNNING transaction owned by
        // nothing (see TransactionGroup.clearFromProcessors()'s ABORTING/ABORTED guard).
        assertFalse(output.contains("ORPHAN_TRANSACTIONS"),
                "OrphanTransactionAudit found a dropped-reference transaction - full diagnostic "
                        + "output:\n" + output);

        // Sanity backstop: the pre-fix run on this exact seed/duration collapsed to 0.0075/s.
        // This catches a future regression that degrades progress WITHOUT producing an outright
        // orphan -- a softer signal than the two assertions above, and a real one. See
        // HEALTHY_FLOOR for the measured basis of the number.
        double throughput = outcome.result().throughput;
        assertTrue(throughput > HEALTHY_FLOOR, String.format(Locale.US,
                "throughput=%.4f/s is below the recovery floor of %.4f/s (pre-fix collapse was "
                        + "0.0075/s; slowest healthy seed measured 0.6620/s). "
                        + "Note the two DIRECT orphan-bug assertions above passed, so if this is "
                        + "the only failure, re-run calibrateHealthyThroughputFloor() before "
                        + "concluding the bug regressed -- the cost model may have moved again.",
                throughput, HEALTHY_FLOOR));
    }


    /**
     * CALIBRATION HARNESS for {@link #HEALTHY_FLOOR}. Not part of the suite; gated on a property
     * so it is re-runnable rather than merely documented:
     *
     * <pre>mvn -o -B test -Dtest=TransactionGroupReentrantAbortRegressionTest -Dts.calibrate.abortFloor=true</pre>
     *
     * <p>WHY THIS EXISTS. The floor is a constant calibrated against a cost model, and this
     * project's cost model has moved five times since the test was written (rollback priced at the
     * work it undoes, group members serialised across IO union CPU, the forward-transition gain
     * removed, lock service cut 1000x, and the ms->us time base). A commented number goes stale the
     * same way the old 1.0 did; a harness that regenerates it does not.
     *
     * <p>Runs are deterministic per seed, so this measures the SPREAD ACROSS SEEDS on this config,
     * not re-runs of one. That is what says whether the guarded seed's throughput is ordinary or
     * unusual for this workload.
     */
    @Test
    @EnabledIfSystemProperty(named = "ts.calibrate.abortFloor", matches = "true")
    void calibrateHealthyThroughputFloor() {
        long[] seeds = { 1000003L, 1000019L, 1000037L, 1000081L, 1000099L };
        SimulatorParameters params = SeedSweepRunner.copyBuilder(configDynamicK50())
                .initialTransactions(16)
                .simulationDurationSeconds(DURATION_S)
                .build();
        double[] tp = new double[seeds.length];
        System.out.println("=== HEALTHY-THROUGHPUT CALIBRATION (current cost basis) ===");
        for (int i = 0; i < seeds.length; i++) {
            List<SimulationRunOutcome> out = new SeedSweepRunner()
                    .runRawWithCensoring("abortFloorCalib", params, new long[] { seeds[i] });
            tp[i] = out.get(0).result().throughput;
            System.out.printf(Locale.US, "  seed=%d  throughput=%.4f/s  terminatedBy=%s%n",
                    seeds[i], tp[i], out.get(0).terminatedBy());
        }
        double min = Arrays.stream(tp).min().orElse(0);
        double max = Arrays.stream(tp).max().orElse(0);
        double mean = Arrays.stream(tp).average().orElse(0);
        double sd = Math.sqrt(Arrays.stream(tp).map(v -> (v - mean) * (v - mean)).sum()
                / Math.max(1, tp.length - 1));
        System.out.printf(Locale.US,
                "  n=%d  min=%.4f  mean=%.4f  max=%.4f  sd=%.4f  (CV %.1f%%)%n",
                tp.length, min, mean, max, sd, 100 * sd / mean);
        System.out.printf(Locale.US, "  pre-fix collapse reference: 0.0075/s -> min is %.0fx it%n",
                min / 0.0075);
    }

    // Exact same shape as DynamicFootprintExperiment/WarmupPilot/VictimSeedFinder's
    // configDynamic() for k=50 (f=0.50, kInitial=10) - the cell this whole investigation centered
    // on.
    private static SimulatorParameters configDynamicK50() {
        int totalModels = 50;
        int propsPerModel = 10;
        int totalProperties = totalModels * propsPerModel;
        int instancePoolSize = Math.max(1, 5000 / totalProperties);
        List<Integer> propertiesPerModel = Collections.nCopies(totalModels, propsPerModel);

        return SimulatorParameters.builder()
                .transactionGenerationMode(TransactionGenerationMode.RANDOM_OPERATIONS)
                .useRandomResourceSelection(true)
                .totalModels(totalModels)
                .propertiesPerModel(propertiesPerModel)
                .instancePoolSize(instancePoolSize)
                .totalLocks(5000)
                .maxTransactionModels(1)
                .useConstantTransactionSize(true)
                .constantTransactionSize(10)
                .semanticOverlap(0.50)
                .avgCprsPerResource(2.0)
                .maxCprsPerResource(6)
                .cprTopologyMode(CprTopologyMode.UNIFORM)
                .semanticOverlapSeed(TOPOLOGY_SEED)
                .numberOfIoProcessors(700)
                .numberOfCpuProcessors(700)
                .concurrencyModel(ConcurrencyModel.ACID)
                .ioTimePerResource(SimulatorParameters.CANONICAL_IO_TIME_PER_RESOURCE)
                .cpuTimePerResource(SimulatorParameters.CANONICAL_CPU_TIME_PER_RESOURCE)
                .lockIOTimePerResource(SimulatorParameters.CANONICAL_LOCK_IO_TIME_PER_RESOURCE)
                .lockCPUTimePerResource(SimulatorParameters.CANONICAL_LOCK_CPU_TIME_PER_RESOURCE)
                .stepTimeSlice(10_000)
                .simulationDurationSeconds(200)
                .build();
    }
}
