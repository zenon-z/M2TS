package test.java.experiments;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import de.tum.tssimulator.bootstrap.ExperimentRuntimeSnapshot;
import de.tum.tssimulator.config.SimulatorParameters;
import de.tum.tssimulator.experiments.SimulationRunner;
import de.tum.tssimulator.experiments.support.WorkloadAxisDefaults;

/**
 * The same configuration and seed must produce the same run, no matter how many runs preceded it
 * in this JVM.
 *
 * <h2>The defect this exists for</h2>
 *
 * <p>{@code TransactionGroup.ID_GENERATOR} is a process-global {@code AtomicLong} that nothing
 * reset. {@code Simulator}'s constructor already reset {@code Transaction.resetMetrics()} and
 * {@code TransactionStateCounter.reset()} - this one sat next to them and was missed, so every run
 * after the first in a process began with group ids continuing from the previous run and executed
 * a different interleaving. Measured at the W1-W4 base cell, one identical config run three times
 * in one process gave <b>1390.1, 1216.7, 1515.6 tps</b> - a 25% spread from a pure artifact.
 *
 * <p><b>Why nothing caught it.</b> The spread was not noise: the sequence reproduced bit-exactly
 * across JVMs, so it looked like ordinary between-seed variance wherever runs were batched. Every
 * multi-run tool - the contention bisection, the MPL sweep, the seed verification, the theta range
 * scan - reported it as seed spread, and the calibration javadoc went as far as recording a
 * "heteroscedastic" pool-dependent variance that was in fact run position. A campaign is the worst
 * case: {@code SeedSweepRunner} runs every cell in one JVM and C30 shuffles cell order, which
 * converts a deterministic artifact into apparent between-cell variance.
 *
 * <p><b>Why it must be a test and not a convention.</b> The failure is reachable only by a
 * change. No single-run check can see it, and no amount of reading {@code Simulator}'s constructor
 * shows an omission; only running twice does. The assertion is bit-equality rather than a
 * tolerance, because the engine is deterministic given a seed and any drift at all is a leak.
 */
class SimulationRunReproducibilityTest {

    /**
     * The horizon and pool are part of the assertion, not incidental scaffolding.
     *
     * <p>A first draft ran at {@code pool = 64} for 3 s and <b>passed with the fix removed</b> -
     * the config was too small for divergent group ids to change any scheduling decision, so the
     * test would have been permanently green against the very defect it names. At {@code pool =
     * 2561} it still passed at 3 s and 6 s, and first failed at <b>10 s</b> (1998.889 vs 1998.778,
     * a 0.006% difference that grows to 25% over a full 60 s run). So the leak needs enough
     * contention AND enough elapsed time for one reordered wake to propagate.
     *
     * <p>This is why the values are pinned here with their evidence rather than tuned for speed:
     * lowering either one silently disarms the test. Overridable only so the sensitivity can be
     * re-measured if the base config moves.
     */
    private static SimulatorParameters shortRun() {
        return WorkloadAxisDefaults.baseBuilder(
                        Integer.getInteger("ts.test.repro.pool", 2561))
                .simulationDurationSeconds(Integer.getInteger("ts.test.repro.seconds", 10))
                .warmupSeconds(1)
                .transactionSamplingSeed(1_000_003L)
                .semanticOverlapSeed(1_000_003L)
                .build();
    }

    @Test
    void identicalConfigRepeatedInOneJvmProducesIdenticalRuns() {
        double[] throughput = new double[3];
        long[] completions = new long[3];
        for (int i = 0; i < throughput.length; i++) {
            ExperimentRuntimeSnapshot snapshot = new SimulationRunner()
                    .runDetailedAllowPartial("repro_" + i, shortRun())
                    .snapshot();
            throughput[i] = snapshot.windowedThroughput();
            completions[i] = snapshot.completionsInWindow();
        }
        for (int i = 1; i < throughput.length; i++) {
            assertEquals(throughput[0], throughput[i], 0.0,
                    "Run " + i + " differs from run 0 at identical config and seed. Some "
                            + "process-global state survives a run and changes the next one's "
                            + "execution - the last such leak was TransactionGroup.ID_GENERATOR. "
                            + "Every batched measurement in this repo is invalid until it is found.");
            assertEquals(completions[0], completions[i],
                    "Completion count differs on run " + i + " at identical config and seed.");
        }
    }
}
