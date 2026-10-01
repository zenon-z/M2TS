package de.tum.tssimulator.experiments;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import de.tum.tssimulator.bookkeeping.CprTopologyMode;
import de.tum.tssimulator.bookkeeping.ReferentialAction;
import de.tum.tssimulator.bootstrap.ExperimentRuntimeSnapshot;
import de.tum.tssimulator.config.ConcurrencyModel;
import de.tum.tssimulator.config.SimulatorParameters;
import de.tum.tssimulator.config.TransactionGenerationMode;
import de.tum.tssimulator.core.Transaction;
import de.tum.tssimulator.experiments.support.AdmissionAttemptRow;
import de.tum.tssimulator.experiments.support.AdmissionAttemptRowWriter;
import de.tum.tssimulator.experiments.support.EffectiveConfigHasher;
import de.tum.tssimulator.experiments.support.ExperimentOutputPaths;
import de.tum.tssimulator.experiments.support.RunLockGuard;
import de.tum.tssimulator.experiments.support.SeedSweepRunner.SweepStats;
import de.tum.tssimulator.experiments.support.SeedSweepRunner;

/**
 * Simulator-side curve for the PostgreSQL-anchored static-locking validation.
 *
 * <p><b>UNITS. One tick = 1 microsecond</b> ({@code SimulatorParameters}'s header), and every
 * constant below is a tick count. Reasoning about these in milliseconds - "300ms service time",
 * "stepTimeSlice=10ms" - puts the absolute scale out by 1000x while leaving every ratio intact,
 * so the conclusions still look right and nothing flags the error. A bare literal carries no
 * unit and is invisible to every tool, so values here are named in ticks and the cost basis is
 * overridable rather than baked in.
 *
 * <p>Cost basis: the canonical 1000 + 1000 = <b>2000 ticks per resource</b>
 * ({@code SimulatorParameters.CANONICAL_*}), against a 2-tick lock service - the 1:1000 lock:data
 * ratio the project decided on. The previous 200/100 predated both the microsecond migration and
 * that decision, and made the ratio 1:150.
 *
 * <p>Step quantum: <b>100 ticks</b>, framed by M8's criterion "service time spans >= 100 steps"
 * (k=25: 500 steps; k=10: 200; D=250/k=5: 100), not by the raw number. Note this is NOT the
 * project-wide 1000 - that was decided on the rung-0 dynamic-footprint cells and does not transfer:
 * at 2000 ticks per resource these cells run 50000 / 20000 / 10000 ticks of service, which a
 * 1000-tick quantum would span in only 50 / 20 / 10 steps, failing the gate on all three. The
 * original quantum finding stands on its own terms - occupancy at MPL=1 came out 0.857 (k=25) /
 * 0.498 (k=10) instead of ~1.0 at a quantum comparable to the service time, and the effect was
 * BEHAVIOURAL rather than a sampling artifact, since throughput itself moved with the step.
 *
 * <p>Cell choice: D=5000,k=25 (D/k^2=8, bound=200) and D=5000,k=10 (D/k^2=50, bound=500) - see
 * the D/k^2-governs-the-ramp correction (TgsPanelBSimCheck's original javadoc history) for why
 * D/k, not D/k^2, was the wrong quantity in the first pass. Neither of these actually SATURATES
 * within a practical load range - the exact hypergeometric doesn't plateau, it approaches its
 * ceiling (D/k) asymptotically, and reaching that ceiling needs load orders of magnitude beyond
 * what's practical to run on either side. P3 is restated as monotone-to-a-ceiling (never
 * decreasing, unlike dynamic locking) plus the collapse contrast (advisory vs for_update), not
 * "reaches a visible plateau." D=250,k=5 (D/k^2=10, ceiling=50) is added specifically to SHOW an
 * actual saturation within range (P(blocked)=0.9997 by n=40) - small ceiling AND wide-enough ramp
 * simultaneously requires small k and small D together, which the two D=5000 cells can't give.
 *
 * <p>Cost-structure fix (unchanged from the first pass): lock-acquisition cost is dropped to
 * near-zero (lockIOTimePerResource=lockCPUTimePerResource=1) so acquisition is negligible
 * against the IO/CPU phases - defensible since Tay's model assumes free acquisition.
 *
 * <p>Also writes first-admission-attempt (heldLockUnits, outcome) rows (AdmissionAttemptRowWriter)
 * for the P1 parity plot - measured P(blocked|H) vs the exact hypergeometric, zero free
 * parameters, the stronger theory-contact panel per the "put the parity panel ahead of the
 * panel-B overlay" ordering. This is a byproduct of the same sweep, not a separate run.
 */
public class TgsPanelBSimCheck {
    private static final String OUTPUT_FILE = "tgs_panelb_sim.csv";
    private static final String FIRST_ATTEMPTS_FILE = "tgs_panelb_sim_first_attempts.csv";
    // V2 (z46): the per-seed record. Named as a sibling of the two above so all
    // three land together under the same outputSuffix.
    private static final String PER_SEED_FILE = "tgs_panelb_sim_per_seed.csv";
    private static final Cell[] CELLS = {
            new Cell(5000, 25, new int[] {1, 2, 4, 8, 12, 16, 24, 32, 48, 64, 96, 128, 192, 256, 384, 512, 640}),
            // V2 (z54) DIAGNOSTIC CELL: (5000,25) at propertiesPerModel=50 gives
            // instancePoolSize = 100, where 11.1%% of roots collapse onto a shared instance and
            // cross-root closure overlap is material. d=50000 raises the pool to 1000 (1.19%%
            // collapse) with mu, D and P untouched, isolating pool size as the single varied
            // parameter. d is divisible by propertiesPerModel, so the granularity-1 assert above
            // holds. mpl list is {1} ALONE so an unfiltered sweep pays one point for it.
            new Cell(50000, 25, new int[] {1}),
            new Cell(5000, 10, new int[] {1, 2, 4, 8, 12, 16, 24, 32, 48, 64, 96, 128, 192, 256, 384, 512, 640}),
            new Cell(5000, 50, new int[] {1, 2, 4, 8, 12, 16, 24, 32, 48, 64, 96, 128, 192, 256, 384, 512, 640}),
            // D/k^2=10, ceiling=50: saturates within range (P(blocked)~0.9997 at n=40) - the
            // explicit-saturation cell neither D=5000 cell can be, per this class's javadoc.
            new Cell(250, 5, new int[] {1, 2, 4, 8, 16, 24, 32, 48, 64, 96, 128, 192, 256, 320}),
            // V1b's operating point. k=100 is chosen because B2 binds there with headroom
            // ((acq+work)/gap = 29 against a threshold of 20, where k=50 returns 21-23); D=80,000
            // then follows from preserving V0's contention regime, since D/k^2 = 8 is the quantity
            // the ladder's cells were built around and k=100 at D=5000 gives 0.50 -- a 16x more
            // contended workload, not a higher-k version of the same one.
            //
            // The MPL ladder is V0's k=25 ladder unchanged, so the two cells are read on the same
            // x-axis. Note W*k reaches 64,000 at the top against totalLocks = 160,000, so even the
            // last rung cannot exhaust the lock space by construction.
            new Cell(80000, 100, new int[] {1, 2, 4, 8, 12, 16, 24, 32, 48, 64, 96, 128, 192, 256, 384, 512, 640}),
            // FOOTPRINT CONTROL for the service-equivalence result. At k=100,
            // f=0.5 a group's lockable footprint is ~100 parent resources + ~50 CU targets
            // = ~150, because SchemaCprFactory builds a target as a DISTINCT Resource on the
            // same instance. Scaling a k=100 parent's service time reproduces the total work
            // but NOT that footprint, so the two arms differed in two ways and the divergence
            // (-81.8% throughput at MPL=256, restarts 1056 vs 0) could not be attributed.
            // k=150 at f=0 matches BOTH: 150 resources of work and 150 lockable resources,
            // and D/k^2 becomes 3.56, which is also the effective regime a 150-resource
            // group already sits in. If this coincides with k=100 f=0.5, footprint is the
            // cause; if it does not, something beyond footprint and service is at work.
            new Cell(80000, 150, new int[] {1, 2, 4, 8, 16, 32, 64, 128, 256}),
            // SIZE-ARM CEILING PROBE. The size arm must vary
            // closure SIZE while holding self-collision DENSITY m(m-1)/2D fixed, which
            // forces D to scale as ~m^2: holding the fan-out-1 density of 0.015312 while
            // m goes 50 -> 200 needs D = 80,000 -> 1,299,600.
            //
            // That is 2 x D = 2.6M lockable Resource objects (totalLocks = d * totalModels),
            // an order of magnitude past the largest cell ever run (D=80,000 -> 160,000,
            // which the comment above flags as the bound the design was checked against).
            // D is NOT a runtime property here -- it comes from this table -- so the probe
            // point has to exist in source before it can be measured.
            //
            // D must be an exact multiple of propertiesPerModel or the granularity assert
            // trips at construction (totalLocks == totalResources holds only then).
            // 1,299,600 = 40 x 32,490. A D taken straight from the m^2 formula (1,299,592)
            // would fail at cell construction -- silently, without even producing a timing.
            //
            // Outcome branches the size arm: completes -> 5 points (25/50/100/200/400,
            // the last needing 10.4M objects); fails or OOMs -> 3 points (25/50/100).
            // k IS NOT THE CASCADE MULTIPLICITY: m = k * f, so Cell(1299600, 200) at
            // f_pg = 0.5 runs m = 100, with cus_per_group reporting 99.88 and the achieved
            // density 0.003809 against a 0.015312 target - 4x off, in the one quantity the size
            // arm exists to hold fixed. m = 200 therefore needs k = m/f = 400. D is unchanged,
            // because 200*199/(2*dens) rounds to the same multiple of 40.
            //
            // THE RULE THAT FOLLOWS: density is computed from cus_per_group (measured, as
            // bookkeepers/groups) and NEVER from intended m or from f_pg_equivalent, which is
            // derived from the semanticOverlap knob and would report f=0.5 while m was 100.
            // D-rounding drift is bounded and predictable; a k/f conflation is unbounded and
            // silent.
            new Cell(1299600, 400, new int[] {32}),
            // FIRST ON-ARM POINT. The cell above and the
            // m=100 anchor it is compared against BOTH sit at D=1,299,600, so density
            // moved 0.0038 -> 0.0153 as a consequence of m doubling. Neither is on the
            // size arm, which is the locus of FIXED density where D ~ m^2. The 2.024x
            // per-CU cost measured across that pair confounds the m effect with a 4x
            // density effect and licenses no extrapolation, so the budget refit stays blocked
            // until a fixed-density pair exists. This is the point that creates one, with the
            // m=200 cell above.
            //
            // m = k * f = 600 * 0.5 = 300. D = 300*299/(2*0.015312) = 2,929,075.2,
            // rounded UP to the multiple of 40 the granularity assert requires:
            // 2,929,080 = 40 * 73,227. Achieved density 0.015312, ratio 1.0000 against
            // the m=200 cell's 0.015303 -- a 0.06% offset from that cell's own D rounding,
            // which is left as-is rather than re-rounded, since changing a D that has
            // already been measured would break the comparability the pair exists for.
            //
            // 2 x D = 5.86M lockable Resource objects, 2.25x the m=200 cell (2.60M). That
            // cell held ~872 MB working set inside -Xmx6g, so ~2 GB is expected here --
            // expected, not established, which is why peak heap is instrumented alongside
            // wall time.
            //
            // SIMULATION_SECONDS is deliberately NOT raised to recover the yield the m=200
            // cell lost (975 completed groups vs the anchor's 3348). Raising it would change
            // the quantity being compared; yield adequacy is open and tracked separately in
            // V2 (i). Read the branch off ELAPSED PER COMPLETED GROUP, not elapsed -- V2 (f)
            // withdrew the wall-clock criterion that the m=200 probe nearly landed on.
            new Cell(2929080, 600, new int[] {32}),
    };
    private static final int SEED_COUNT_PROPERTY_DEFAULT = 3;
    // Reduced from 1000s (the step=1000ms-era default) - at step=10ms this is already 100x more
    // outer-loop steps per virtual second than before; kept short enough to stay tractable while
    // still giving many complete cycles per cell (cycle time ~500-7500ms depending on k, so 200s
    // is tens to hundreds of completions even at MPL=1).
    private static final int SIMULATION_SECONDS = 200;
    private static final String SEED_COUNT_PROPERTY = "ts.tgsPanelB.seedCount";
    private static final String CHEAP_LOCK_MS_PROPERTY = "ts.tgsPanelB.cheapLock";

    // Cost basis and quantum, overridable so the PREVIOUS basis can still be reproduced for
    // comparison (-Dts.tgsPanelB.ioTime=200 -Dts.tgsPanelB.cpuTime=100 -Dts.tgsPanelB.stepSlice=10)
    // rather than living only in git history. See the class javadoc's unit note.
    private static final String IO_TIME_PROPERTY = "ts.tgsPanelB.ioTime";
    private static final String CPU_TIME_PROPERTY = "ts.tgsPanelB.cpuTime";
    private static final String STEP_SLICE_PROPERTY = "ts.tgsPanelB.stepSlice";
    // Comma-separated "D:k" list restricting which cells run; empty = all. The k=50 cell is in
    // CELLS for Panel B but has no PostgreSQL counterpart (the P1 cells are k = 25/10/5), so a
    // comparison against the Postgres arm can skip it:
    //   -Dts.tgsPanelB.cellFilter=5000:25,5000:10,250:5
    private static final String CELL_FILTER_PROPERTY = "ts.tgsPanelB.cellFilter";
    // V1 (deferred constraint triggers). V0 is this harness with f = 0 and one model; V1 is the
    // SAME harness with the CPR knobs opened, which is what keeps the ladder's frozen-parameter
    // claim checkable -- the cost fields are untouched between rungs, only the CPR axis moves.
    // These two must be properties rather than constants: hard-coding totalModels and
    // semanticOverlap to 1 and 0.0 pins the harness to its no-CPR configuration by construction.
    private static final String SEMANTIC_OVERLAP_PROPERTY = "ts.tgsPanelB.semanticOverlap";
    private static final String TOTAL_MODELS_PROPERTY = "ts.tgsPanelB.totalModels";
    private static final String OVERLAP_SEED_PROPERTY = "ts.tgsPanelB.semanticOverlapSeed";
    private static final String PROPS_PER_MODEL_PROPERTY = "ts.tgsPanelB.propertiesPerModel";
    // Cascade fan-out mu: sets avgCprsPerResource AND maxCprsPerResource to the same value,
    // which is the point -- see the comment at the call site. Default 1 = V0/V1/V1b.
    private static final String FANOUT_PROPERTY = "ts.tgsPanelB.fanout";
    // Comma-separated MPL list restricting the ladder; empty = all. V0's ladder is a 9-point subset
    // of the 17 configured, and running only those cuts a comparison run from ~1.6h to ~30min.
    private static final String MPL_FILTER_PROPERTY = "ts.tgsPanelB.mplFilter";
    // Suffix appended to both output filenames. Both writers TRUNCATE (see the class javadoc's
    // warning), so a comparison run without a suffix destroys the baseline it is compared against.
    private static final String OUTPUT_SUFFIX_PROPERTY = "ts.tgsPanelB.outputSuffix";
    private static final int DEFAULT_IO_TIME = 1000;   // canonical, = SimulatorParameters' default
    private static final int DEFAULT_CPU_TIME = 1000;  // canonical
    // 100 ticks, NOT the project-wide 1000. That figure was decided on the rung-0 dynamic-footprint
    // cells; it does not transfer here. At the canonical 2000 ticks per resource these cells run
    // 50000 / 20000 / 10000 ticks of service (k = 25 / 10 / 5), so a 1000-tick quantum spans only
    // 50 / 20 / 10 steps and FAILS M8's >= 100-quantum gate on all three. 100 is the coarsest value
    // that clears every cell (500 / 200 / 100 steps), and the slice sweep's measured convergence
    // (1000 -> 100 moves the most slice-sensitive arm 0.2-0.3%) covers it.
    private static final long DEFAULT_STEP_SLICE = 100;

    /** Quantile of a per-group-observation lineage array; 0 when the run produced no observations.
     * Wanted alongside the mean because four mechanism hypotheses have now died, three of them
     * inferred from the shape of a mean-vs-load curve. mean/median separates a shifted distribution
     * (constant per-wait overhead) from a scaled one (proportionally longer waits) from a
     * heavier-tailed one (a subset disadvantaged) - and only the third can produce a k-dependence,
     * since a k-lock transaction needs all k free simultaneously, an extreme-value quantity. */
    private static double semanticOverlapOf() {
        return Double.parseDouble(System.getProperty(SEMANTIC_OVERLAP_PROPERTY, "0.0"));
    }

    /**
     * EVERY per-seed metric, name to extractor, in one table. V2 (z46).
     *
     * <p>The harness reduced each metric to mean/stddev/ci95 at {@code summarizeMetric} and wrote
     * only the mean, discarding the per-seed values at the aggregation boundary. That is fine for
     * a marginal question and fails for every other kind: V2 (z45) needed the COVARIANCE between
     * attempted and groups to estimate sigma_sim, and from marginals alone could only bound it to
     * [0.006%, 2.592%] - two orders of magnitude. (z41b) had already hit the same wall and answered
     * it by adding three ci columns, metric by metric, as each question arose.
     *
     * <p>There are 20 such call sites in this class and 60 across the experiments tree, so this is
     * a CLASS of missing measurement rather than two instances. Emitting the raw per-seed rows once
     * answers any covariance or distributional question without a further harness change - the way
     * one complete copyBuilder would close that class.
     *
     * <p>The per-seed file is the PRIMARY record; the aggregate CSV is a derived convenience, so a
     * metric present here and absent from the aggregate is expected.
     */
    private static final Map<String, SeedSweepRunner.MetricExtractor> SEED_METRICS =
            perSeedMetrics();

    private static Map<String, SeedSweepRunner.MetricExtractor> perSeedMetrics() {
        Map<String, SeedSweepRunner.MetricExtractor> m = new LinkedHashMap<>();
        m.put("throughput", o -> o.result().throughput);
        m.put("mean_locks_held_count_sampled", o -> o.snapshot().meanLocksHeldCountSampled());
        m.put("peak_locks_held_count", o -> (double) o.snapshot().peakLocksHeldCount());
        m.put("mean_running_state_count_sampled", o -> o.snapshot().meanRunningStateCountSampled());
        m.put("peak_running_state_count", o -> (double) o.snapshot().peakRunningStateCount());
        m.put("mean_admission_waiter_count_sampled", o -> o.snapshot().meanAdmissionWaiterCountSampled());
        m.put("peak_admission_waiter_count", o -> (double) o.snapshot().peakAdmissionWaiterCount());
        m.put("lineage_admission_wait", o -> meanOf(o.snapshot().groupLifetimeObservationAdmissionWait()));
        m.put("lineage_pending_queue", o -> meanOf(o.snapshot().groupLifetimeObservationPendingQueue()));
        m.put("lineage_backoff", o -> meanOf(o.snapshot().groupLifetimeObservationBackoff()));
        m.put("lineage_running", o -> meanOf(o.snapshot().groupLifetimeObservationRunning()));
        m.put("lineage_wait_p50", o -> quantileOf(o.snapshot().groupLifetimeObservationAdmissionWait(), 0.50));
        m.put("lineage_wait_p90", o -> quantileOf(o.snapshot().groupLifetimeObservationAdmissionWait(), 0.90));
        m.put("attempted_bookkeepers", o -> (double) o.snapshot().attemptedBookkeepersTotal());
        m.put("bookkeepers_created", o -> (double) o.snapshot().bookkeepersCreatedTotal());
        m.put("groups_completed", o -> (double) o.snapshot().groupsCompletedTotal());
        m.put("total_admission_attempts", o -> (double) o.snapshot().totalAdmissionAttemptsCount());
        m.put("total_blocked_at_check", o -> (double) o.snapshot().totalBlockedAtCheckCount());
        m.put("restarts_total", o -> (double) o.snapshot().restartsTotal());
        m.put("blocked_at_check_rate", o -> {
            long total = o.snapshot().totalAdmissionAttemptsCount();
            return total <= 0 ? 0.0 : (double) o.snapshot().totalBlockedAtCheckCount() / total;
        });
        return Collections.unmodifiableMap(m);
    }

    /** Header for the per-seed file: the key columns, then SEED_METRICS in declaration order. */
    private static String perSeedHeader() {
        return "D,k,mpl,seed,seed_index," + String.join(",", SEED_METRICS.keySet());
    }

    /** One row per seed, written as each cell completes and never batched (V2 (z12) rule 1). */
    private static void writePerSeed(PrintWriter w, List<SimulationRunner.SimulationRunOutcome> outcomes,
            long[] seeds, int d, int k, int mpl) {
        for (int i = 0; i < outcomes.size(); i++) {
            SimulationRunner.SimulationRunOutcome o = outcomes.get(i);
            StringBuilder sb = new StringBuilder();
            sb.append(d).append(',').append(k).append(',').append(mpl).append(',')
              .append(i < seeds.length ? seeds[i] : -1L).append(',').append(i);
            for (SeedSweepRunner.MetricExtractor ex : SEED_METRICS.values()) {
                sb.append(',').append(String.format(Locale.US, "%.6f", ex.extract(o)));
            }
            w.println(sb);
        }
        w.flush();
    }

    private static double quantileOf(long[] values, double q) {
        if (values == null || values.length == 0) {
            return 0.0;
        }
        long[] copy = values.clone();
        Arrays.sort(copy);
        int idx = (int) Math.min(Math.round(q * (copy.length - 1)), copy.length - 1L);
        return copy[idx];
    }

    /** Mean of a per-group-observation lineage array; 0 when the run produced no observations. */
    private static double meanOf(long[] values) {
        if (values == null || values.length == 0) {
            return 0.0;
        }
        long sum = 0;
        for (long v : values) {
            sum += v;
        }
        return (double) sum / values.length;
    }

    private record Cell(int d, int k, int[] mplValues) {
        int bound() {
            return d / k;
        }
    }

    public static void main(String[] args) throws IOException {
        // Host measurement lock. A wrapper script only helps when it is invoked;
        // Maven, an IDE launch and a bare `java -cp` all bypass it.
        RunLockGuard.require("TgsPanelBSimCheck");

        // First-attempt (H, outcome) rows for the P1 parity plot - see class javadoc. Read once
        // at JVM start, before any SimulationMetrics is constructed (matches the repo's other
        // -Dts.* diagnostic-toggle convention).
        System.setProperty("ts.metrics.admissionAttemptTrace", "true");
        // V2 (z42): SeedSweepRunner.withSeed() overwrites BOTH transactionSamplingSeed and
        // semanticOverlapSeed with the sweep seed, so -Dts.tgsPanelB.semanticOverlapSeed is
        // silently discarded. V2 (z41) burned a 15-run cross-arm probe on exactly that: five
        // "topology realizations" came back byte-identical because all five ran seed 1000003, and
        // the CSV's own seed column recorded it. Abort rather than run, because the failure mode
        // is a plausible-looking result, not an error.
        //
        // NOT fixed by making withSeed honour the property: 19 other experiments set it to a
        // constant and route through the same sweep, and changing what they realize would
        // invalidate results already collected. The defect is the silence. For decorrelated
        // topology and sampling seeds use SeedSweepRunner.saltedSeeds(count, cellSalt).
        if (System.getProperty(OVERLAP_SEED_PROPERTY) != null) {
            throw new IllegalStateException(OVERLAP_SEED_PROPERTY + " is set, but SeedSweepRunner"
                    + " overwrites it with the sweep seed, so it would have no effect. Vary "
                    + SEED_COUNT_PROPERTY + " instead (it drives both seeds together), or use"
                    + " SeedSweepRunner.saltedSeeds() if the two must move independently.");
        }
        int seedCount = Integer.getInteger(SEED_COUNT_PROPERTY, SEED_COUNT_PROPERTY_DEFAULT);
        int cheapLock = Integer.getInteger(CHEAP_LOCK_MS_PROPERTY, 1);
        long[] seeds = SeedSweepRunner.defaultSeeds(seedCount);
        SeedSweepRunner sweepRunner = new SeedSweepRunner();

        String suffix = System.getProperty(OUTPUT_SUFFIX_PROPERTY, "");
        String outputPath = ExperimentOutputPaths.resultsFile(
                OUTPUT_FILE.replace(".csv", suffix + ".csv"));
        String firstAttemptsPath = ExperimentOutputPaths.resultsFile(
                FIRST_ATTEMPTS_FILE.replace(".csv", suffix + ".csv"));
        String perSeedPath = ExperimentOutputPaths.resultsFile(
                PER_SEED_FILE.replace(".csv", suffix + ".csv"));
        int totalCells = 0;
        String mplFilter = System.getProperty(MPL_FILTER_PROPERTY, "");
        Set<Integer> wantedMpl = new LinkedHashSet<>();
        if (!mplFilter.isBlank()) {
            for (String token : mplFilter.split(",")) {
                wantedMpl.add(Integer.parseInt(token.trim()));
            }
        }
        String cellFilter = System.getProperty(CELL_FILTER_PROPERTY, "");
        Set<String> wanted = new LinkedHashSet<>();
        if (!cellFilter.isBlank()) {
            for (String token : cellFilter.split(",")) {
                wanted.add(token.trim());
            }
        }
        for (Cell cell : CELLS) {
            if (wanted.isEmpty() || wanted.contains(cell.d() + ":" + cell.k())) {
                for (int mpl : cell.mplValues()) {
                    if (wantedMpl.isEmpty() || wantedMpl.contains(mpl)) {
                        totalCells++;
                    }
                }
            }
        }
        if (!wanted.isEmpty()) {
            System.out.println("Cell filter active (" + cellFilter + "): " + totalCells
                    + " cell-points of " + CELLS.length + " configured cells");
        }
        if (!wanted.isEmpty() && totalCells == 0) {
            // A cell filter naming a D:k that is not in CELLS matches nothing, which without
            // this check runs the loop zero times, writes a header-only CSV, prints "Results
            // exported to:" and exits 0 - a run that measures nothing while producing every
            // outward sign of success.
            //
            // It is easy to hit: a triage run launched at cellFilter=1299640:400 (the D from
            // the density formula) against a CELLS entry of 1299600:400 (the D actually probed)
            // differs by 0.06% of D, immaterial to any measurement -- but as a FILTER KEY it is a
            // total miss rather than a near-miss, and the only symptom is a 1.5-hour cell
            // finishing in three seconds.
            //
            // Fail loudly instead. An empty selection is always a typo or a stale D, never intent:
            // running no cells is expressed by not running the program.
            throw new IllegalArgumentException(String.format(
                    "cellFilter=%s matched 0 of %d configured cells (mplFilter=%s). "
                    + "Configured D:k are %s. Nothing would be measured; refusing to exit 0.",
                    cellFilter, CELLS.length,
                    mplFilter.isBlank() ? "(none)" : mplFilter,
                    Arrays.stream(CELLS).map(c -> c.d() + ":" + c.k())
                            .collect(Collectors.joining(", "))));
        }
        int currentCell = 0;

        try (PrintWriter writer = new PrintWriter(new FileWriter(outputPath));
                PrintWriter perSeedWriter = new PrintWriter(new FileWriter(perSeedPath));
                AdmissionAttemptRowWriter firstAttemptsWriter = new AdmissionAttemptRowWriter(firstAttemptsPath)) {
            perSeedWriter.println(perSeedHeader());
            writer.println("D,k,bound,mpl,throughput_mean,throughput_ci,"
                    + "mean_locks_held_count_sampled_mean,peak_locks_held_count_mean,n_mean,"
                    + "mean_running_state_count_sampled_mean,peak_running_state_count_mean,"
                    + "mean_admission_waiter_count_sampled_mean,peak_admission_waiter_count_mean,"
                    + "blocked_at_check_rate_mean,total_admission_attempts_mean,total_blocked_at_check_mean,"
                    + "restarts_total_mean,seed_count,success_count,watchdog_count,"
                    + "lineage_admission_wait_mean,lineage_pending_queue_mean,"
                    + "lineage_backoff_mean,lineage_running_mean,"
                    + "lineage_wait_p50,lineage_wait_p90,"
                    // V1 fire-count parity: CUs created per completed group, against PostgreSQL's
                    // trigger fires per commit. Both predict k*f. Emitted as TOTALS so the
                    // comparison is a ratio of totals, matching how the PostgreSQL side counts
                    // (marginal, never conditioned on per-attempt state) -- and because at small f
                    // the per-transaction count is a binomial draw whose individual values would
                    // disagree for a non-defect reason.
                    + "semantic_overlap,bookkeepers_created_mean,groups_completed_mean,"
                    // attempted vs created makes the visited set OBSERVABLE.
                    //
                    // cus_dropped IS NOT ZERO, and "identity correspondence means distinct
                    // sources give distinct targets" does not make it so: measured, it runs
                    // 0 / 1.0 / 3.8 / 9.6 per run at f_pg = 0.05 / 0.125 / 0.25 / 0.5.
                    //
                    // Identity correspondence holds only on the INSTANCE. SchemaCprFactory builds
                    // the target as `new Resource(resource.instanceId(), link.target())`, and
                    // Resource is (instanceId, property) -- so two sources on the SAME instance
                    // whose links point to the same target PROPERTY collide. Normalised by
                    // groups_completed, the drop rate is m(m-1)/(2D) to within 1.1 Poisson sd in
                    // every cell, with the constant matching 1/(2D) = 6.25e-6: ordinary birthday
                    // collisions among m targets drawn over D.
                    //
                    // Consequence: a real composition difference from PostgreSQL, whose trigger
                    // has no visited set and would UPDATE the same model_b row twice. It is
                    // m/(2D) ~ 0.03% of CUs at f = 0.5, and the identity R_sim = 1 + E[m]/k is
                    // unaffected because m counts CREATED CUs. Recorded, not corrected.
                    //
                    // NOTE cus_dropped also counts the ACID/BASE filter, so a nonzero value needs
                    // disambiguating rather than being attributed to the visited set outright.
                    // total/eligible make the f conversion CHECKABLE from the output rather than
                    // assumed. PostgreSQL's f is "fraction of the parent's footprint that emits";
                    // the simulator's is "fraction of the GLOBAL property pool with an outgoing
                    // link". They relate as f_pg = f_sim * (total / eligible) -- NOT by a constant
                    // 2. The 2 is this configuration's instance of it (LINE at totalModels=2 makes
                    // exactly half the properties eligible); change the topology, model count or
                    // property split and the factor changes with it.
                    + "total_properties,eligible_properties,f_pg_equivalent,"
                    + "attempted_bookkeepers_mean,cus_dropped,"
                    // V1 criterion 4 ("parameters frozen"): a digest over the COST fields only.
                    // The whole-config hash cannot serve here -- a V1 cell legitimately differs
                    // from V0 in semanticOverlap/totalModels/totalResources, so a full-config
                    // comparison between rungs always differs and asserts nothing. These fields
                    // are overridable at run time (-Dts.tgsPanelB.ioTime/.cpuTime/.stepSlice/
                    // .cheapLock), which is exactly why the value a run ACTUALLY used has to be
                    // recorded rather than reconstructed from the code afterwards.
                    + "cus_per_group,"
                    // V2 (z41b): cus_per_group is bookkeepersStats.mean()/groupsCompletedStats.mean()
                    // -- a RATIO OF MEANS, so it cannot carry per-seed spread no matter how many
                    // seeds run. (z41a) needed exactly that spread to ask whether the structural
                    // response is seed-degenerate, and could not be evaluated. These three are the
                    // underlying plain summarizeMetric stats, which DO have it.
                    + "bookkeepers_created_ci,groups_completed_ci,attempted_bookkeepers_ci,"
                    // L6 of V0 makes this gate BINDING -- "mean group service time ... span
                    // >= ~100 decision quanta ... Report service_to_quantum_ratio per validated
                    // cell" -- and it was emitted NOWHERE: not in any CSV, Java source, or
                    // analysis script. Checking V0's margin in 2026-09 required computing it by
                    // hand from the configuration. A binding gate whose quantity never reaches an
                    // artifact is a gate nobody can check after the fact, so it is emitted here,
                    // in the row writer, where it travels with the data rather than living in an
                    // analysis script that may not be re-run.
                    //
                    // SCOPE, because L6 and TgsStepQuantumDiagnostic word it differently: this is
                    // the PARENT's service, k*(io + cpu + lockIO + lockCPU), over the decision
                    // quantum. L6 says "group service time" and the diagnostic says "IO+CPU
                    // service time"; the two differ by the lock phase. The lock terms are included
                    // here so the value is the larger, more conservative reading of the gate. It
                    // does NOT include cascade work, so for a cell with CPRs it is a lower bound
                    // on the group's service.
                    + "service_to_quantum_ratio,cost_config_hash,status");

            for (Cell cell : CELLS) {
                if (!wanted.isEmpty() && !wanted.contains(cell.d() + ":" + cell.k())) {
                    continue;
                }
                for (int mpl : cell.mplValues()) {
                    if (!wantedMpl.isEmpty() && !wantedMpl.contains(mpl)) {
                        continue;
                    }
                    currentCell++;
                    SimulatorParameters params = cellParameters(cell.d(), cell.k(), mpl, cheapLock);
                    String runName = String.format(Locale.US, "tgsPanelBSim_D%d_k%d_mpl%d",
                            cell.d(), cell.k(), mpl);

                    System.out.printf(Locale.US, "[%d/%d] TGS Panel B sim: D=%d k=%d bound=%d mpl=%d%n",
                            currentCell, totalCells, cell.d(), cell.k(), cell.bound(), mpl);

                    var outcomes = sweepRunner.runRaw(runName, params, seeds);

                    String configId = String.format(Locale.US, "D%d_k%d_mpl%d", cell.d(), cell.k(), mpl);
                    for (int i = 0; i < outcomes.size(); i++) {
                        var outcome = outcomes.get(i);
                        long seed = i < seeds.length ? seeds[i] : -1;
                        writeFirstAttempts(firstAttemptsWriter, outcome.snapshot(), configId, seed,
                                cell.d(), cell.k(), mpl);
                    }
                    writePerSeed(perSeedWriter, outcomes, seeds, cell.d(), cell.k(), mpl);

                    SweepStats throughputStats = SeedSweepRunner.summarizeMetric(outcomes, seeds.length,
                            outcome -> outcome.result().throughput);
                    SweepStats meanLocksHeldStats = SeedSweepRunner.summarizeMetric(outcomes, seeds.length,
                            outcome -> outcome.snapshot().meanLocksHeldCountSampled());
                    SweepStats peakLocksHeldStats = SeedSweepRunner.summarizeMetric(outcomes, seeds.length,
                            outcome -> (double) outcome.snapshot().peakLocksHeldCount());
                    SweepStats meanRunningStats = SeedSweepRunner.summarizeMetric(outcomes, seeds.length,
                            outcome -> outcome.snapshot().meanRunningStateCountSampled());
                    SweepStats peakRunningStats = SeedSweepRunner.summarizeMetric(outcomes, seeds.length,
                            outcome -> (double) outcome.snapshot().peakRunningStateCount());
                    SweepStats meanWaiterStats = SeedSweepRunner.summarizeMetric(outcomes, seeds.length,
                            outcome -> outcome.snapshot().meanAdmissionWaiterCountSampled());
                    SweepStats peakWaiterStats = SeedSweepRunner.summarizeMetric(outcomes, seeds.length,
                            outcome -> (double) outcome.snapshot().peakAdmissionWaiterCount());
                    // Lineage-time decomposition, the measurement that separates "waiting for the
                    // lock to free" from "free, but not yet dispatched". Postgres has essentially no
                    // second component - its retry loop takes the set the moment it is available -
                    // so if the simulator's throughput shortfall under contention lives in
                    // PENDING_QUEUE rather than ADMISSION_WAIT, that is a dispatch-path cost with no
                    // counterpart in the real system. The four sum exactly to lineage time.
                    SweepStats lineageAdmissionWaitStats = SeedSweepRunner.summarizeMetric(outcomes,
                            seeds.length, o -> meanOf(o.snapshot().groupLifetimeObservationAdmissionWait()));
                    SweepStats lineagePendingQueueStats = SeedSweepRunner.summarizeMetric(outcomes,
                            seeds.length, o -> meanOf(o.snapshot().groupLifetimeObservationPendingQueue()));
                    SweepStats lineageBackoffStats = SeedSweepRunner.summarizeMetric(outcomes,
                            seeds.length, o -> meanOf(o.snapshot().groupLifetimeObservationBackoff()));
                    SweepStats lineageRunningStats = SeedSweepRunner.summarizeMetric(outcomes,
                            seeds.length, o -> meanOf(o.snapshot().groupLifetimeObservationRunning()));
                    SweepStats lineageWaitP50Stats = SeedSweepRunner.summarizeMetric(outcomes,
                            seeds.length, o -> quantileOf(o.snapshot().groupLifetimeObservationAdmissionWait(), 0.50));
                    SweepStats lineageWaitP90Stats = SeedSweepRunner.summarizeMetric(outcomes,
                            seeds.length, o -> quantileOf(o.snapshot().groupLifetimeObservationAdmissionWait(), 0.90));
                    // LINE gives every model but the last an outgoing edge, so eligible =
                    // (totalModels - 1) * propertiesPerModel. Derived here rather than imported so
                    // a topology change shows up as a mismatch instead of silently altering the
                    // conversion; if this stops matching CprSchemaBuilder's eligible pool, the
                    // f_pg_equivalent column is what will disagree first.
                    int propsPerModelOut = Integer.getInteger(PROPS_PER_MODEL_PROPERTY, 1);
                    int totalModelsOut = Integer.getInteger(TOTAL_MODELS_PROPERTY, 1);
                    int totalProperties = totalModelsOut * propsPerModelOut;
                    int eligibleProperties = Math.max(0, totalModelsOut - 1) * propsPerModelOut;
                    SweepStats attemptedBkStats = SeedSweepRunner.summarizeMetric(outcomes,
                            seeds.length, o -> (double) o.snapshot().attemptedBookkeepersTotal());
                    SweepStats bookkeepersStats = SeedSweepRunner.summarizeMetric(outcomes,
                            seeds.length, o -> (double) o.snapshot().bookkeepersCreatedTotal());
                    SweepStats groupsCompletedStats = SeedSweepRunner.summarizeMetric(outcomes,
                            seeds.length, o -> (double) o.snapshot().groupsCompletedTotal());
                    SweepStats blockedRateStats = SeedSweepRunner.summarizeMetric(outcomes, seeds.length,
                            outcome -> {
                                long total = outcome.snapshot().totalAdmissionAttemptsCount();
                                long blocked = outcome.snapshot().totalBlockedAtCheckCount();
                                return total <= 0 ? 0.0 : (double) blocked / total;
                            });
                    SweepStats totalAttemptsStats = SeedSweepRunner.summarizeMetric(outcomes, seeds.length,
                            outcome -> (double) outcome.snapshot().totalAdmissionAttemptsCount());
                    SweepStats totalBlockedStats = SeedSweepRunner.summarizeMetric(outcomes, seeds.length,
                            outcome -> (double) outcome.snapshot().totalBlockedAtCheckCount());
                    SweepStats restartsStats = SeedSweepRunner.summarizeMetric(outcomes, seeds.length,
                            outcome -> (double) outcome.snapshot().restartsTotal());

                    String status = throughputStats.allWatchdog() ? "WATCHDOG"
                            : throughputStats.watchdogCount() > 0 ? "PARTIAL" : "OK";
                    double nMean = meanLocksHeldStats.mean() / cell.k();

                    writer.printf(Locale.US,
                            "%d,%d,%d,%d,%.6f,%.6f,%.3f,%.3f,%.4f,%.3f,%.3f,%.3f,%.3f,%.6f,%.1f,%.1f,%.3f,%d,%d,%d,"
                            + "%.1f,%.1f,%.1f,%.1f,%.1f,%.1f,%.4f,%.1f,%.1f,%d,%d,%.4f,%.1f,%.1f,%.4f,"
                            + "%.4f,%.4f,%.4f,"
                            + "%.1f,%s,%s%n",
                            cell.d(), cell.k(), cell.bound(), mpl,
                            throughputStats.mean(), throughputStats.ci95(),
                            meanLocksHeldStats.mean(), peakLocksHeldStats.mean(), nMean,
                            meanRunningStats.mean(), peakRunningStats.mean(),
                            meanWaiterStats.mean(), peakWaiterStats.mean(),
                            blockedRateStats.mean(), totalAttemptsStats.mean(), totalBlockedStats.mean(),
                            restartsStats.mean(),
                            throughputStats.seedCount(), throughputStats.successCount(),
                            throughputStats.watchdogCount(),
                            lineageAdmissionWaitStats.mean(), lineagePendingQueueStats.mean(),
                            lineageBackoffStats.mean(), lineageRunningStats.mean(),
                            lineageWaitP50Stats.mean(), lineageWaitP90Stats.mean(),
                            Double.parseDouble(System.getProperty(SEMANTIC_OVERLAP_PROPERTY, "0.0")),
                            bookkeepersStats.mean(), groupsCompletedStats.mean(),
                            totalProperties, eligibleProperties,
                            eligibleProperties == 0 ? 0.0
                                    : semanticOverlapOf() * totalProperties / eligibleProperties,
                            attemptedBkStats.mean(),
                            attemptedBkStats.mean() - bookkeepersStats.mean(),
                            groupsCompletedStats.mean() <= 0 ? 0.0
                                    : bookkeepersStats.mean() / groupsCompletedStats.mean(),
                            bookkeepersStats.ci95(), groupsCompletedStats.ci95(),
                            attemptedBkStats.ci95(),
                            params.stepTimeSlice <= 0 ? 0.0
                                    : (double) cell.k() * (params.ioTimePerResource
                                            + params.cpuTimePerResource
                                            + params.lockIOTimePerResource
                                            + params.lockCPUTimePerResource)
                                      / params.stepTimeSlice,
                            EffectiveConfigHasher.hashCostFields(params),
                            status);
                    writer.flush();

                    Transaction.resetMetrics();
                }
            }
        }

        System.out.println("Results exported to: " + outputPath);
        System.out.println("First-attempt (H,outcome) rows for the P1 parity plot: " + firstAttemptsPath);
        System.out.println("Per-seed rows (V2 (z46), the primary record): " + perSeedPath);
    }

    private static void writeFirstAttempts(AdmissionAttemptRowWriter writer,
            ExperimentRuntimeSnapshot snapshot,
            String configId, long seed, int d, int k, int mpl) throws IOException {
        long[] virtualTime = snapshot.admissionAttemptVirtualTime();
        int[] nRunning = snapshot.admissionAttemptNRunning();
        int[] heldLockUnits = snapshot.admissionAttemptHeldLockUnits();
        String[] outcomes = snapshot.admissionAttemptOutcome();
        for (int i = 0; i < virtualTime.length; i++) {
            writer.write(new AdmissionAttemptRow(configId, seed, d, k, mpl, 0.0,
                    virtualTime[i], nRunning[i], heldLockUnits[i], outcomes[i]));
        }
    }

    /** Same A2 config traps as TgsStage0Recon/Stage 2 intend (uniform access, one-lock-per-
     * resource, constant k, f=0, non-binding processor pool), plus the cheap-lock override and
     * the step-quantum fix this class's javadoc explains. */
    private static SimulatorParameters cellParameters(int d, int k, int mpl, int cheapLock) {
        double semanticOverlap = Double.parseDouble(
                System.getProperty(SEMANTIC_OVERLAP_PROPERTY, "0.0"));
        int totalModels = Integer.getInteger(TOTAL_MODELS_PROPERTY, 1);
        int propsPerModel = Integer.getInteger(PROPS_PER_MODEL_PROPERTY, 1);
        int fanout = Integer.getInteger(FANOUT_PROPERTY, 1);
        if (fanout < 1 || fanout > propsPerModel) {
            throw new IllegalArgumentException(String.format(
                    "fanout=%d must be >= 1 and <= propertiesPerModel=%d: a source cannot have "
                    + "more distinct targets than the next model has properties",
                    fanout, propsPerModel));
        }
        int ioTime = Integer.getInteger(IO_TIME_PROPERTY, DEFAULT_IO_TIME);
        int cpuTime = Integer.getInteger(CPU_TIME_PROPERTY, DEFAULT_CPU_TIME);
        long stepSlice = Long.getLong(STEP_SLICE_PROPERTY, DEFAULT_STEP_SLICE);
        // CONTENTION-PATH GATE. LockGranularityMapper sets granularity =
        // ceil(totalResources / totalLocks); this comparison REQUIRES granularity 1, because the
        // PostgreSQL arm takes one advisory lock per row. Granularity 2 shipped silently once and
        // cost up to +55.7% throughput at MPL 128 while criterion 1 -- which compares fire COUNTS
        // -- moved -0.3% and passed at all 24 checked cells. Nothing observable in V1's criteria
        // catches this, so it is asserted structurally rather than measured.
        //
        // Deliberately local to this harness, NOT in SimulatorConfig: R5 varies lock granularity
        // on purpose, and a global invariant would break it.
        int expectedResources = (d / propsPerModel) * totalModels * propsPerModel;
        int configuredLocks = d * totalModels;
        if (configuredLocks != expectedResources) {
            throw new IllegalStateException(String.format(
                    "lock granularity must be 1 for the PostgreSQL comparison: totalLocks=%d but "
                    + "totalResources=%d (d=%d, totalModels=%d, propertiesPerModel=%d) -> "
                    + "granularity %d. One advisory lock per row on the PostgreSQL side means the "
                    + "simulator must have one lock per resource.",
                    configuredLocks, expectedResources, d, totalModels, propsPerModel,
                    (int) Math.ceil(expectedResources / (double) configuredLocks)));
        }
        return SimulatorParameters.builder()
                .transactionGenerationMode(TransactionGenerationMode.RANDOM_OPERATIONS)
                .useRandomResourceSelection(true)
                .totalModels(totalModels)
                // f resolution is 1/totalProperties (CprSchemaBuilder rounds
                // totalProperties * semanticOverlap), so one property per model gives the f axis
                // only TWO reachable states. propsPerModel raises the resolution while
                // instancePoolSize holds totalResources fixed at instancePool * sum(props).
                .propertiesPerModel(Collections.nCopies(totalModels, propsPerModel))
                // Pin ORIGINAL transactions to model 0, the emitting model, when running V1.
                // PostgreSQL's parent samples all k ids from model_a; an unpinned simulator would
                // spread its footprint across both models, halving the fire count AND letting the
                // visited set drop CUs aimed at parent-held targets -- a mechanism PostgreSQL has
                // no counterpart for that grows with f.
                .transactionModelIndices(totalModels > 1 ? List.of(0)
                                                         : List.of())
                .instancePoolSize(d / propsPerModel)
                // totalLocks must equal totalResources or LockGranularityMapper coarsens:
                // granularity = ceil(totalResources / totalLocks). With CPRs enabled
                // totalResources is d * totalModels, so .totalLocks(d) would give granularity 2 -
                // model A's d resources sharing d/2 locks, HALVING the simulator's effective lock
                // space against a PostgreSQL arm that takes one advisory lock per model_a row.
                // That is false contention with no counterpart in the comparator, and the
                // fire-count criterion cannot catch it, since fire counts do not depend on
                // locking. Reduces to d when totalModels = 1.
                .totalLocks(d * totalModels)
                .maxTransactionModels(1)
                .useConstantTransactionSize(true)
                .constantTransactionSize(k)
                // f = 0 reproduces V0 exactly, so the default keeps every existing V0 run
                // bit-identical. V1 sweeps this and nothing else on the cost side.
                .semanticOverlap(semanticOverlap)
                // FAN-OUT, ONE KNOB ON PURPOSE (V2 density arm). Default 1 keeps every V0/V1/V1b
                // run bit-identical: one propagated write per written source row, matching the
                // trigger's FOR EACH ROW at identity correspondence.
                //
                // avgCprsPerResource and maxCprsPerResource are set TOGETHER and are deliberately
                // NOT separately settable. CprSchemaBuilder.buildPropertyLinks draws out-degree
                // from a binomial with q = (avg-1)/(max-1); setting avg == max collapses it to
                // q = 1, i.e. a DETERMINISTIC out-degree -- which is what a fires-every-time
                // trigger does. Exposing the two separately would let a caller set avg < max and
                // silently reintroduce a stochastic out-degree against a deterministic trigger,
                // making the arms disagree for a reason unrelated to the cascade. V2's plan warns
                // against exactly that ("Do not leave mu < m at this rung"), and V2 (u2) records
                // that the determinism comes from this equality and NOT from propertiesPerModel,
                // which is why P is free to be the density axis.
                .avgCprsPerResource(fanout)
                .maxCprsPerResource(fanout)
                // LINE at totalModels=2 gives the edge set {A -> B}: B emits nothing, so cascade
                // depth is capped at 1 BY STRUCTURE, matching a single non-recursive trigger.
                // UNIFORM would let B link back to A and validate a depth-2 cascade against a
                // depth-1 trigger.
                .cprTopologyMode(CprTopologyMode.LINE)
                .referentialAction(ReferentialAction.ON_CASCADE)
                .writeFraction(1.0)
                // Always the 12345L default in practice: the guard in main() rejects an explicit
                // setting, and SeedSweepRunner.withSeed() overwrites whatever lands here anyway
                // (V2 (z41)/(z42)). Kept so the builder call reads completely.
                .semanticOverlapSeed(Long.getLong(OVERLAP_SEED_PROPERTY, 12345L))
                .numberOfIoProcessors(700)
                .numberOfCpuProcessors(700)
                .concurrencyModel(ConcurrencyModel.ACID)
                .initialTransactions(mpl)
                .lockIOTimePerResource(cheapLock)
                .lockCPUTimePerResource(cheapLock)
                // Canonical cost basis (1000 + 1000 = 2000 ticks per resource). The previous
                // 200/100 predated the microsecond migration and the 1:1000 lock:data decision -
                // against a 2-tick lock service it made the ratio 1:150, not 1:1000.
                .ioTimePerResource(ioTime)
                .cpuTimePerResource(cpuTime)
                .stepTimeSlice(stepSlice)
                .simulationDurationSeconds(SIMULATION_SECONDS)
                .build();
    }
}
