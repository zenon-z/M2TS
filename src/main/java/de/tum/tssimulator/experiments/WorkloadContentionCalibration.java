package de.tum.tssimulator.experiments;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import de.tum.tssimulator.bookkeeping.CprGraphMetrics;
import de.tum.tssimulator.bookkeeping.CprTopologyMode;
import de.tum.tssimulator.bootstrap.ExperimentRuntimeSnapshot;
import de.tum.tssimulator.config.SimulatorParameters;
import de.tum.tssimulator.core.Model;
import de.tum.tssimulator.core.ModelFactory;
import de.tum.tssimulator.core.Transaction;
import de.tum.tssimulator.cpr.CprSchema;
import de.tum.tssimulator.cpr.CprSchemaBuilder;
import de.tum.tssimulator.experiments.support.ExperimentOutputPaths;
import de.tum.tssimulator.experiments.support.WorkloadAxisDefaults;
import de.tum.tssimulator.locking.policy.CautiousWaitingPolicy;

/**
 * Pre-campaign gate. Nothing in the campaign runs until this passes.
 *
 * <p>Two parts, in order:
 *
 * <ol>
 *   <li><b>Pre-flight</b> (no simulation): per-topology {@code f} ceiling, exact representability
 *       of every swept {@code f}, no out-degree truncation, M8's quantum floor, and lock aliasing
 *       proven off. These are deterministic properties of the configuration, so they cost nothing
 *       and must never be discovered mid-sweep.</li>
 *   <li><b>Contention bisection</b>: find the {@code instancePoolSize} that puts the base cell's
 *       {@code frac_blocked} in [0.15, 0.35], so the figures have room to move in both
 *       directions.</li>
 * </ol>
 *
 * <h2>Why instancePoolSize and not totalLocks</h2>
 *
 * <p>{@code totalLocks} looks like the contention knob and is the wrong one. Shrinking the lock
 * table produces <em>false sharing</em> - two distinct resources contending for one lock - which is
 * a different mechanism from data contention. Worse, {@code LockGranularityMapper}'s default
 * {@code "modulo"} strategy groups by instance-major global ordinal, so an aliased lock group spans
 * consecutive <em>instances</em>; Zipf's hot instances are ids 1, 2, 3..., the lowest ordinals. Lock
 * aliasing would therefore concentrate in exactly the place theta concentrates and amplify theta's
 * apparent effect through an artifact that reads as a mechanism. {@code instancePoolSize} sets D
 * directly and concentrates real conflicts on real resources.
 *
 * <h2>Reading the verdict, if the bisection fails</h2>
 *
 * <p>The two contention signals are ORDERED, not redundant.
 * {@link CautiousWaitingPolicy#evaluate} hard-denies (aborts the requester) only when some other
 * group's holder is <em>itself blocked</em>, and soft-denies (waits) otherwise. So contention shows
 * up as waiting first and aborting second: at shallow contention every denial is a soft deny and
 * {@code aborts_per_completion} is legitimately ~0. That is the shallow-contention regime, NOT a
 * null and NOT grounds to widen the search.
 *
 * <ul>
 *   <li><b>Primary, first-order:</b> lock-wait share of worker time.</li>
 *   <li><b>Confirmatory, second-order:</b> the hard-deny RATE from
 *       {@link CautiousWaitingPolicy#getHardDenyFiredTotal()} /
 *       {@link CautiousWaitingPolicy#getEvaluationsTotal()} - used rather than
 *       {@code aborts_per_completion} because those two counters separate "evaluate() ran but never
 *       hard-denied" from "evaluate() never ran", which an abort count cannot do.</li>
 * </ul>
 *
 * <p>Conclude "MPL = cores is too low a load for this instrument" only when lock-wait share is at
 * floor across the whole range AND {@code evaluationsTotal > 0} proves the policy was consulted.
 */
public final class WorkloadContentionCalibration {

    private static final String OUTPUT_FILE = "wl_contention_calibration.csv";

    /** The band the base cell must land in: room to move in both directions. */
    private static final double TARGET_BLOCKED_LO = 0.15;
    private static final double TARGET_BLOCKED_HI = 0.35;

    /**
     * Bisection bounds on instancePoolSize. The floor is deliberately low: D = 200 * pool, and at
     * MPL=8 x k=10 only ~80 resources are live, so contention does not appear until D is small.
     * A first pass floored at 16 bottomed out at frac_blocked = 0.077 - short of the band - which
     * was a property of the SEARCH RANGE, not of the instrument. The real floor is the pigeonhole
     * gate, which is checked per cell rather than guessed at here.
     */
    private static final int POOL_MIN = 2;
    private static final int POOL_MAX = 4096;

    /**
     * Ceiling on live-resource occupancy {@code (MPL * k) / D}. Above this the footprints in flight
     * approach the size of the resource space itself, conflicts are forced by counting rather than
     * measured (V3's pigeonhole concern, one level up), and the engine has pre-existing faults that
     * only appear there.
     */
    private static final double MAX_LIVE_OCCUPANCY = 0.15;

    /** V3's rule in its analogue form: co-burst offers against the reachable key space. */
    private static final double PIGEONHOLE_LIMIT = 0.2;

    /** Every f the campaign sweeps, including the control at 0. */
    private static final double[] SWEPT_F = {0.0, 0.05, 0.25, 0.80};
    /**
     * The single model graph the campaign now uses. Panel D varies ALPHA (in-degree concentration)
     * inside it rather than swapping named topologies, so there is one Stage-1 structure to check
     * rather than three - which is also what removed Panel D's cyclicity confound.
     */
    private static final CprTopologyMode[] SWEPT_TOPOLOGIES = {CprTopologyMode.UNIFORM};

    private WorkloadContentionCalibration() {
    }

    public static void main(String[] args) throws IOException {
        preflight();

        // Verification mode: instead of bisecting, run ONE pool at N seeds to measure the spread.
        // The bisection is n=1, and near the band edge the between-seed spread is comparable to
        // the band's own width - so a single-seed "calibrated" value is PROVISIONAL until
        // its variance has been looked at. -Dts.wl.verifyPool=<n> -Dts.wl.verifySeeds=<k>
        String mplSweep = System.getProperty("ts.wl.mplSweep");
        if (mplSweep != null && !mplSweep.isBlank()) {
            mplSweepAtConstantContention(mplSweep);
            return;
        }

        int verifyPool = Integer.getInteger("ts.wl.verifyPool", 0);
        if (verifyPool > 0) {
            // MPL, not CORES. Defaulting this to CORES measures contention at 24 concurrent
            // transactions while the campaign runs at 192: at pool 2561 the two readings are
            // capacity share 0.464 (MPL=24) and 0.598 (MPL=192).
            verify(verifyPool, Integer.getInteger("ts.wl.verifySeeds", 3),
                    Integer.getInteger("ts.wl.verifyMpl", WorkloadAxisDefaults.MPL));
            return;
        }

        String path = ExperimentOutputPaths.resultsFile(OUTPUT_FILE);
        try (PrintWriter out = new PrintWriter(new FileWriter(path))) {
            out.println("instance_pool,d,total_locks,granularity,throughput,completions,"
                    + "frac_blocked,frac_waiting_room,waiting_room_lock_fraction,aborts_per_completion,"
                    + "hard_deny_rate,hard_deny_fired,evaluations,pigeonhole_ratio,"
                    + "cu_service_to_quantum,terminated_by,measurement_window_s,lock_wait_p95,lock_wait_samples,"
                    + "interpretable,in_band");
            bisect(out);
        }
        System.out.println("\nWrote " + path);
    }

    // ---- part 1: pre-flight ------------------------------------------------------------

    /**
     * Deterministic configuration checks. Fails loudly and early rather than mid-sweep, and prints
     * every realised value so a reader can see what was actually asked for versus what the
     * generator can represent.
     */
    private static void preflight() {
        System.out.println("=== W1-W4 pre-flight ===");
        int p = WorkloadAxisDefaults.TOTAL_PROPERTIES;
        System.out.printf(Locale.US, "totalModels=%d propertiesPerModel=%d -> %d properties, "
                + "f quantises to 1/%d = %.4f%n",
                WorkloadAxisDefaults.TOTAL_MODELS, WorkloadAxisDefaults.PROPERTIES_PER_MODEL, p, p, 1.0 / p);

        boolean failed = false;

        // (a) f representability. round(P*f)/P must equal f exactly, or the cell reports a value
        //     it did not run. At 50 properties f=0.05 becomes 0.06 this way.
        for (double f : SWEPT_F) {
            int activeCount = (int) Math.round(p * f);
            double realised = activeCount / (double) p;
            boolean ok = Math.abs(realised - f) < 1e-12;
            System.out.printf(Locale.US, "  f=%.2f -> activeCount=%d realised=%.4f %s%n",
                    f, activeCount, realised, ok ? "OK" : "*** NOT REPRESENTABLE ***");
            failed |= !ok;
        }

        // (b)+(c) per-topology feasibility and out-degree, tested by ACTUALLY BUILDING the schema.
        //     LINE and HOTSPOT only let models WITH an outgoing edge emit, so f cannot exceed
        //     (n-1)/n, and CprSchemaBuilder throws (with the computed ceiling in its message)
        //     rather than clipping. Attempting the build uses the generator's own feasibility rule
        //     as the single source of truth instead of reimplementing (n-1)/n here, where a second
        //     copy could drift from the one that actually governs.
        for (CprTopologyMode topology : SWEPT_TOPOLOGIES) {
            List<Model> pool = new ModelFactory(WorkloadAxisDefaults.INSTANCE_POOL_SIZE)
                    .createModels(Collections.nCopies(
                            WorkloadAxisDefaults.TOTAL_MODELS, WorkloadAxisDefaults.PROPERTIES_PER_MODEL));
            System.out.printf("  topology=%s%n", topology);

            for (double f : SWEPT_F) {
                if (f == 0.0) {
                    continue; // the no-CPR control: no schema to build
                }
                CprSchema schema;
                try {
                    schema = new CprSchemaBuilder(pool, topology, f,
                            WorkloadAxisDefaults.BASE_MU, WorkloadAxisDefaults.BASE_M, 67890L,
                            WorkloadAxisDefaults.BASE_ALPHA,
                            WorkloadAxisDefaults.BASE_ACYCLIC_TOTAL_ORDER).build();
                } catch (IllegalArgumentException e) {
                    System.out.printf(Locale.US, "    f=%.2f *** INFEASIBLE *** %s%n", f, e.getMessage());
                    failed = true;
                    continue;
                }
                CprGraphMetrics metrics = CprGraphMetrics.from(schema, p);
                double realisedMu = metrics.sources() == 0 ? 0.0
                        : metrics.edges() / (double) metrics.sources();
                // 4-sigma band on a binomial mean over `sources` draws: mu is the MEAN of a draw
                // and will not land on the nominal value exactly, so an exact check would fail on
                // sampling noise alone. The structural exactness check is (b)+(c) above.
                double q = (WorkloadAxisDefaults.BASE_MU - 1.0) / (WorkloadAxisDefaults.BASE_M - 1.0);
                double sigma = Math.sqrt((WorkloadAxisDefaults.BASE_M - 1) * q * (1 - q)
                        / Math.max(1, metrics.sources()));
                boolean muOk = Math.abs(realisedMu - WorkloadAxisDefaults.BASE_MU) <= 4 * sigma + 1e-9;
                System.out.printf(Locale.US,
                        "    f=%.2f sources=%d edges=%d realised_mu=%.4f (nominal %.2f, 4sigma=%.4f) %s%n",
                        f, metrics.sources(), metrics.edges(), realisedMu,
                        WorkloadAxisDefaults.BASE_MU, 4 * sigma, muOk ? "OK" : "*** MU OFF ***");
                failed |= !muOk;
            }
        }

        // (d) M8 quantum floor, on the CU - the binding transaction, since it is always one
        //     resource and therefore has the SHORTEST service in the system.
        SimulatorParameters base = WorkloadAxisDefaults.baseBuilder().build();
        double ratio = WorkloadAxisDefaults.cuServiceToQuantum(base);
        boolean quantumOk = ratio >= WorkloadAxisDefaults.M8_MIN_SERVICE_QUANTA;
        System.out.printf(Locale.US, "  M8: cu_service/stepTimeSlice = %.1f (floor %.0f) %s%n",
                ratio, WorkloadAxisDefaults.M8_MIN_SERVICE_QUANTA, quantumOk ? "OK" : "*** QUANTISED ***");
        failed |= !quantumOk;

        // (e) horizon, echoed rather than assumed. These come from system properties read at class
        //     init, so a -D that does not reach the forked JVM silently leaves the defaults in
        //     place - and every per-cell fraction below is computed over this window.
        System.out.printf(Locale.US, "  horizon: simulationDurationSeconds=%d warmupSeconds=%d "
                + "stepTimeSlice=%d ticks%n",
                base.simulationDurationSeconds, base.warmupSeconds, base.stepTimeSlice);

        // (f) lock aliasing off, checked on the REALISED granularity rather than trusting the field.
        try {
            WorkloadAxisDefaults.assertLockAliasingOff(base);
            System.out.println("  lock aliasing: OFF (granularity 1) OK");
        } catch (IllegalStateException e) {
            System.out.println("  *** " + e.getMessage());
            failed = true;
        }

        if (failed) {
            throw new IllegalStateException("W1-W4 pre-flight FAILED - see the report above. "
                    + "No cell may run until every line reads OK.");
        }
        System.out.println("pre-flight OK\n");
    }

    // ---- part 2: contention bisection --------------------------------------------------

    /**
     * <b>The bisection BRACKETS; it does not calibrate.</b> Each probe is a single seed, and near
     * the band edge the between-seed spread (~+/-0.02) is comparable to the band's own width. A
     * search that stops the moment a cell reads in-band therefore selects the favourable tail of
     * the seed distribution, not the centre - measured: its pool=29 answer has a 5-seed mean of
     * 0.1448, below the band it was reported as satisfying. Optimistic in expectation, the same
     * shape as the pilot-power projection.
     *
     * <p>So treat the result as a bracket and confirm the candidate with
     * {@code -Dts.wl.verifyPool=<n> -Dts.wl.verifySeeds=5} before pinning anything.
     */
    private static void bisect(PrintWriter out) {
        System.out.println("=== contention bisection on instancePoolSize (BRACKETING ONLY) ===");
        System.out.println("Single-seed probes; confirm any candidate with -Dts.wl.verifyPool before pinning.");
        System.out.printf(Locale.US, "target frac_blocked in [%.2f, %.2f]%n",
                TARGET_BLOCKED_LO, TARGET_BLOCKED_HI);

        int lo = POOL_MIN;
        int hi = POOL_MAX;
        Integer chosen = null;
        Cell best = null;

        // A smaller pool means a smaller D and therefore MORE contention, so frac_blocked is
        // DECREASING in instancePoolSize - the bisection direction is inverted relative to the
        // usual "bigger knob, bigger effect" reading.
        while (lo <= hi) {
            int pool = lo + (hi - lo) / 2;
            Cell cell = runCell(pool);
            emit(out, cell);
            System.out.println("  " + cell);

            // Find the LARGEST in-band pool, not merely AN in-band pool. frac_blocked is
            // decreasing in pool size, so the first in-band cell a plain bisection meets is
            // whichever one it happened to probe - stopping there discards every larger pool that
            // also satisfies the band. Pool size is not a free variable here: it is the number of
            // instances the theta axis has to skew over, so among equally valid contention levels
            // the largest pool is strictly the better design point.
            if (cell.fracBlocked >= TARGET_BLOCKED_LO) {
                if (cell.inBand()) {
                    chosen = pool;
                    best = cell;
                }
                lo = pool + 1;   // enough contention -> a BIGGER pool may still qualify
            } else {
                hi = pool - 1;   // too little contention -> need a SMALLER pool
            }
            if (best == null || Math.abs(midpoint() - cell.fracBlocked)
                    < Math.abs(midpoint() - best.fracBlocked)) {
                best = cell;
            }
        }

        System.out.println();
        if (chosen != null) {
            System.out.printf(Locale.US,
                    "CALIBRATED: instancePoolSize=%d (D=%d), frac_blocked=%.3f%n",
                    chosen, WorkloadAxisDefaults.totalResources(chosen), best.fracBlocked);
            System.out.println("Pin this in WorkloadAxisDefaults.INSTANCE_POOL_SIZE and pre-register it.");
            return;
        }

        // No pool landed in the band. Read the ORDERED signals before concluding anything.
        System.out.println("NO instancePoolSize in [" + POOL_MIN + ", " + POOL_MAX + "] landed in the band.");
        System.out.println("Closest cell: " + best);
        System.out.println();
        // Refuse a verdict before checking the cells were measurements at all. A bisection that
        // steers on single-digit completion counts produces a confident-looking answer from noise -
        // the "output shaped so it cannot fail" failure, and the one this probe is most
        // exposed to because every signal it reads is a ratio.
        if (!best.interpretable()) {
            System.out.printf(Locale.US,
                    "VERDICT: INCONCLUSIVE - no cell was a measurement. Closest had %d completions "
                            + "(floor %d) and terminated_by=%s. Every signal here is a ratio over the "
                            + "completion count or the window, so at this count they are noise, not "
                            + "contention. Raise ts.wl.simulationSeconds until completions clear the "
                            + "floor, then re-run; do NOT read the fractions above.%n",
                    best.completions, Cell.MIN_COMPLETIONS, best.terminatedBy);
        } else if (best.evaluations == 0) {
            System.out.println("VERDICT: INCONCLUSIVE - evaluationsTotal == 0, so the waiting policy was "
                    + "never consulted. That is an instrumentation problem, not a contention finding.");
        } else if (best.lockWaitSamples == 0) {
            // "MPL = cores" is stale phrasing from when the two were equal; they are now 192 and
            // 24. The verdict is about the MPL, which is the offered-load knob.
            System.out.println("VERDICT: MPL = " + WorkloadAxisDefaults.MPL + " cannot exhibit CPR "
                    + "effects on this instrument. NO transaction ever waited for a lock across the whole "
                    + "range (lock_wait_samples == 0) while evaluationsTotal > 0 proves the policy ran. "
                    + "This is a finding about the design; it is NOT a licence to raise MPL silently.");
        } else {
            System.out.printf(Locale.US,
                    "VERDICT: contention EXISTS but never reaches the band - lock_wait_p95=%.0f ticks over "
                            + "%d samples, frac_blocked peaked at %.3f, hard_deny_rate=%.4f. The knob is "
                            + "working and the range simply does not go far enough, OR the band is wrong for "
                            + "this design point. A zero hard-deny rate on its own is the SHALLOW-CONTENTION "
                            + "regime, not a null.%n",
                    best.lockWaitP95, best.lockWaitSamples, best.fracBlocked, best.hardDenyRate());
        }
    }

    /** One pool, many seeds - the spread is what decides whether a band-edge value is real. */
    private static void verify(int pool, int seeds, int mpl) {
        System.out.printf(Locale.US, "=== verifying instancePoolSize=%d at MPL=%d over %d seeds ===%n",
                pool, mpl, seeds);
        double[] blocked = new double[seeds];
        double[] conflict = new double[seeds];
        double[] capacityShare = new double[seeds];
        long lastRequests = 0L;
        for (int i = 0; i < seeds; i++) {
            long seed = 1_000_003L * (i + 1);
            Cell cell = runCell(pool, seed, mpl);
            blocked[i] = cell.fracBlocked();
            conflict[i] = cell.lockConflictRate();
            capacityShare[i] = cell.throughput() / WorkloadAxisDefaults.referenceCapacityTps(
                    WorkloadAxisDefaults.CORES, WorkloadAxisDefaults.TRANSACTION_LENGTH);
            lastRequests = cell.lockRequestsTotal();
            System.out.printf(Locale.US, "  seed=%-10d %s%n", seed, cell);
        }
        double mean = 0.0;
        for (double b : blocked) {
            mean += b;
        }
        mean /= seeds;
        double sd = 0.0;
        for (double b : blocked) {
            sd += (b - mean) * (b - mean);
        }
        sd = seeds > 1 ? Math.sqrt(sd / (seeds - 1)) : 0.0;
        double lo = Double.MAX_VALUE;
        double hi = -Double.MAX_VALUE;
        for (double b : blocked) {
            lo = Math.min(lo, b);
            hi = Math.max(hi, b);
        }
        System.out.printf(Locale.US, "%nfrac_blocked mean=%.4f sd=%.4f min=%.4f max=%.4f%n",
                mean, sd, lo, hi);
        System.out.printf(Locale.US, "SENSITIVITY INPUT: pool=%d D=%d mean=%.4f sd=%.4f%n",
                pool, WorkloadAxisDefaults.totalResources(pool), mean, sd);
        // The REPLACEMENT target. frac_blocked above is retained for comparison only: it is a
        // time-weighted occupancy fraction over a small blocked population and could not resolve
        // the band at n=5 (sd 0.07-0.19 against a 0.20-wide band) across four rounds of bisection.
        // Conflict rate is conflicts/requests - two large counts - so its sampling error is orders
        // of magnitude smaller, and it is a monotone function of MPL*k/D, which is what the pool
        // actually sets.
        double cMean = 0.0;
        for (double c : conflict) {
            cMean += c;
        }
        cMean /= seeds;
        double cSd = 0.0;
        for (double c : conflict) {
            cSd += (c - cMean) * (c - cMean);
        }
        cSd = seeds > 1 ? Math.sqrt(cSd / (seeds - 1)) : 0.0;
        System.out.printf(Locale.US,
                "CONFLICT RATE (diagnostic): mean=%.5f sd=%.5f CV=%.1f%%  requests=%d%n",
                cMean, cSd, cMean == 0 ? 0.0 : 100.0 * cSd / cMean, lastRequests);

        // THE CALIBRATION TARGET: throughput as a share of the control-verified analytic capacity.
        //
        // Chosen over both occupancy statistics after measuring DISCRIMINATING POWER - separation
        // between candidate pools in units of within-pool sd - rather than CV alone:
        //   frac_blocked   1793 vs 2561: delta 0.249  / sd 0.133   = 1.87 sd
        //   conflict rate  1793 vs 2561: delta 0.00042/ sd 0.00047 = 0.89 sd
        // The conflict rate is 2-4x less NOISY and roughly half as DISCRIMINATING, because its
        // signal shrinks with its variance - a per-access probability moves 1.2x over a pool range
        // where throughput moves severalfold. Picking it on CV would have been the same error as
        // reading in_gini for concentration or par_run for parallelism: a statistic that looks
        // better on its own summary while answering a different question.
        //
        // Throughput has both properties: large counts AND large signal. It is also the only one
        // of the three that states something directly - "the pool at which contention costs X% of
        // analytic capacity" belongs in a setup paragraph; a per-access conflict probability does
        // not.
        double tMean = 0.0;
        for (double t : capacityShare) {
            tMean += t;
        }
        tMean /= seeds;
        double tSd = 0.0;
        for (double t : capacityShare) {
            tSd += (t - tMean) * (t - tMean);
        }
        tSd = seeds > 1 ? Math.sqrt(tSd / (seeds - 1)) : 0.0;
        System.out.printf(Locale.US,
                "CAPACITY SHARE (target): mean=%.4f sd=%.4f CV=%.1f%%  (throughput / %.0f tps analytic)%n",
                tMean, tSd, tMean == 0 ? 0.0 : 100.0 * tSd / tMean,
                WorkloadAxisDefaults.referenceCapacityTps(WorkloadAxisDefaults.CORES,
                        WorkloadAxisDefaults.TRANSACTION_LENGTH));
        boolean everySeedInBand = lo >= TARGET_BLOCKED_LO && hi <= TARGET_BLOCKED_HI;
        System.out.printf(Locale.US, "every seed in [%.2f, %.2f]: %s%n",
                TARGET_BLOCKED_LO, TARGET_BLOCKED_HI, everySeedInBand ? "YES" : "NO");
        if (!everySeedInBand) {
            System.out.println("The single-seed bisection value is NOT robust at this pool - the "
                    + "between-seed spread straddles the band edge. Pick a pool whose whole spread "
                    + "clears the edge, or state the band as approximate and say so in the plan.");
        }
    }

    /**
     * Choose a base MPL by sweeping offered concurrency at CONSTANT contention.
     *
     * <p><b>Why the pool is recalibrated at every point.</b> {@code frac_blocked} is not a property
     * of the workload alone - it is driven by {@code (MPL * k) / D}. At a fixed
     * {@code instancePoolSize} a sweep from MPL 24 to 192 moves live-resource occupancy from ~6%
     * to ~46% and {@code frac_blocked} by ~1.7x, so throughput along the axis is a mixture of
     * admission pressure and lock contention and a plateau cannot be attributed to either. Holding
     * {@code frac_blocked} fixed by re-bisecting the pool per point isolates admission, which is
     * the quantity "pick MPL at saturation" is about.
     *
     * <p>{@code D} therefore becomes a REPORTED COVARIATE rather than a pinned constant, and
     * {@code frac_blocked} plus the pigeonhole ratio are emitted on every row so the sweep stays
     * readable after the fact whichever way a later reader wants to slice it.
     */
    private static void mplSweepAtConstantContention(String spec) throws IOException {
        String path = ExperimentOutputPaths.resultsFile("wl_mpl_sweep.csv");
        System.out.println("=== MPL sweep at CONSTANT contention (pool recalibrated per point) ===");
        System.out.printf(Locale.US, "target frac_blocked in [%.2f, %.2f]%n%n",
                TARGET_BLOCKED_LO, TARGET_BLOCKED_HI);
        System.out.printf(Locale.US, "%6s %7s %8s %10s %9s %9s %9s %9s%n",
                "MPL", "pool", "D", "tps", "blocked", "wait_rm", "util", "n");

        try (PrintWriter out = new PrintWriter(new FileWriter(path))) {
            out.println("mpl,instance_pool,d,throughput,frac_blocked,frac_waiting_room,"
                    + "pigeonhole_ratio,completions,unit_utilisation,in_band");
            for (String tok : spec.split(",")) {
                int mpl = Integer.parseInt(tok.trim());
                // Bisect the pool for THIS MPL - largest pool still at or above the band's floor,
                // same rule the base calibration uses.
                int lo = POOL_MIN;
                int hi = POOL_MAX;
                Cell chosen = null;
                while (lo <= hi) {
                    int pool = lo + (hi - lo) / 2;
                    // Occupancy guard. Live resources are MPL*k; a pool where that approaches or
                    // exceeds D is not a contended configuration, it is a degenerate one - and the
                    // bisection will walk into it unprompted, because "more contention" always
                    // looks like progress toward the band. Two distinct pre-existing engine faults
                    // (an ABORTED member assigned to a unit; the affinity invariant firing with
                    // affinity ON) were reached this way at MPL >= 48, so the search is pruned
                    // rather than allowed to explore configurations the instrument cannot run.
                    double occupancy = (double) mpl * WorkloadAxisDefaults.TRANSACTION_LENGTH
                            / WorkloadAxisDefaults.totalResources(pool);
                    if (occupancy > MAX_LIVE_OCCUPANCY) {
                        lo = pool + 1; // too crowded - need a BIGGER pool
                        continue;
                    }
                    System.out.printf(Locale.US, "    probe mpl=%d pool=%d D=%d occ=%.1f%%%n",
                            mpl, pool, WorkloadAxisDefaults.totalResources(pool), 100.0 * occupancy);
                    System.out.flush();
                    Cell c = runCell(pool, 1_000_003L, mpl);
                    if (c.fracBlocked() >= TARGET_BLOCKED_LO) {
                        if (c.inBand()) {
                            chosen = c;
                        }
                        lo = pool + 1;
                    } else {
                        hi = pool - 1;
                    }
                }
                if (chosen == null) {
                    System.out.printf(Locale.US, "%6d %7s %8s %10s   no pool in band%n", mpl, "-", "-", "-");
                    continue;
                }
                System.out.printf(Locale.US, "%6d %7d %8d %10.2f %9.3f %9.3f %8.1f%% %9d%n",
                        mpl, chosen.instancePool(), chosen.d(), chosen.throughput(),
                        chosen.fracBlocked(), chosen.fracWaitingRoom(),
                        100.0 * chosen.unitUtilisation(), chosen.completions());
                out.printf(Locale.US, "%d,%d,%d,%.4f,%.6f,%.6f,%.6f,%d,%.6f,%s%n",
                        mpl, chosen.instancePool(), chosen.d(), chosen.throughput(),
                        chosen.fracBlocked(), chosen.fracWaitingRoom(), chosen.pigeonholeRatio(),
                        chosen.completions(), chosen.unitUtilisation(), chosen.inBand());
                out.flush();
            }
        }
        System.out.println();
        System.out.println("Pick the first MPL where throughput plateaus - that is the closed-loop");
        System.out.println("equivalent of 'saturated by offered load'. Contention is held fixed across");
        System.out.println("the axis, so a plateau is admission, not locking.");
        System.out.println();
        System.out.println("Wrote " + path);
    }

    private static double midpoint() {
        return (TARGET_BLOCKED_LO + TARGET_BLOCKED_HI) / 2.0;
    }

    private static Cell runCell(int instancePoolSize) {
        return runCell(instancePoolSize, 1_000_003L);
    }

    private static Cell runCell(int instancePoolSize, long seed) {
        // MPL, not CORES - the two are independent parameters. A CORES default here calibrates
        // contention at MPL=24 while the campaign runs at 192, reporting frac_blocked ~0.18 where
        // the real value is ~0.51. That kind of carried-over default is this project's most
        // frequent single error, and it shows up only as a 3x disagreement between two
        // measurements that should agree.
        return runCell(instancePoolSize, seed, WorkloadAxisDefaults.MPL);
    }

    private static Cell runCell(int instancePoolSize, long seed, int mpl) {
        SimulatorParameters params = WorkloadAxisDefaults.baseBuilder(instancePoolSize)
                .initialTransactions(mpl)
                // RECORDED admission capacity, not -Dts.simulator.disableMplLimit. Equal to the
                // live population = "the population is the only concurrency control", which is the
                // closed-loop equivalent of an open-loop system saturated by offered load. Being a
                // parameter, it reaches config_hash, copyBuilder and every output row; the system
                // property reaches none of them.
                .maxConcurrentRunning(Integer.getInteger("ts.wl.maxRunning", 0) > 0
                        ? Math.min(Integer.getInteger("ts.wl.maxRunning", 0), mpl) : mpl)
                .transactionSamplingSeed(seed)
                .semanticOverlapSeed(seed)
                .build();
        WorkloadAxisDefaults.assertLockAliasingOff(params);
        WorkloadAxisDefaults.assertMplIsRecorded(params);

        // CautiousWaitingPolicy's diagnostics are PROCESS-GLOBAL statics and
        // Transaction.resetMetrics() does NOT touch them - they have their own reset. So
        // SeedSweepRunner's `finally { Transaction.resetMetrics(); }` leaves them accumulating
        // across every run, on the sweep path as much as on a direct SimulationRunner call. Left
        // alone, the first run's reading is the only clean one and every later run reports a
        // running total, which would make the hard-deny rate look like it rises with run index.
        // Reset, then ASSERT the reset took - a convention that is not enforced is not a fact.
        CautiousWaitingPolicy.resetDiagnosticCounters();
        assertCountersClean();

        SimulationRunner.SimulationRunOutcome outcome =
                new SimulationRunner().runDetailedAllowPartial("wl_calib_pool" + instancePoolSize, params);
        ExperimentRuntimeSnapshot snapshot = outcome.snapshot();
        var lockWait = snapshot.lockWaitStats();

        long evaluations = CautiousWaitingPolicy.getEvaluationsTotal();
        long hardDenies = CautiousWaitingPolicy.getHardDenyFiredTotal();
        Transaction.resetMetrics();

        int d = WorkloadAxisDefaults.totalResources(instancePoolSize);
        // Windowed (post-warmup) getters, matching SeedSweepRunner's extraction exactly.
        // abortsPerCompletion is computed from restartsTotal/groupsCompletedTotal rather than from
        // snapshot.abortsPerCompletion(): that accessor is misleadingly named and wired to a
        // MEMBER-level counter (parent AND every bookkeeper), ~4x the group-level figure. Using it
        // here would inflate the second-order signal against a group-level denominator.
        double abortsPerCompletion = snapshot.groupsCompletedTotal() == 0 ? 0.0
                : snapshot.restartsTotal() / (double) snapshot.groupsCompletedTotal();
        return new Cell(instancePoolSize, d, params.totalLocks,
                snapshot.windowedThroughput(),
                snapshot.completionsInWindow(),
                snapshot.windowedFractionTimeBlocked(),
                snapshot.windowedFractionTimeWaitingRoom(),
                snapshot.lockSecondsWaitingRoomFraction(),
                abortsPerCompletion,
                hardDenies, evaluations,
                pigeonholeRatio(params, instancePoolSize),
                WorkloadAxisDefaults.cuServiceToQuantum(params),
                outcome.terminatedBy().name(),
                snapshot.measurementWindowS(),
                lockWait != null ? lockWait.p95 : 0.0,
                lockWait != null ? lockWait.sampleCount : 0,
                utilisation(params, outcome, durationTicksOf(params)),
                snapshot.lockConflictRate(), snapshot.lockRequestsTotal());
    }

    /**
     * The reset must be a MEASURED fact, not an assumption - these are statics shared by every run
     * in the JVM, and a missed reset silently turns each reading into a cumulative total.
     */
    /** Delivered IO+CPU service over processor-time available - the UTILISATION side. */
    private static double utilisation(SimulatorParameters params,
            SimulationRunner.SimulationRunOutcome outcome, double durationTicks) {
        double unitTicks = (double) (params.numberOfIoProcessors + params.numberOfCpuProcessors)
                * durationTicks;
        return unitTicks <= 0 ? 0.0
                : (outcome.result().totalSimulatedIOTime + outcome.result().totalSimulatedCPUTime) / unitTicks;
    }

    private static double durationTicksOf(SimulatorParameters params) {
        return (double) params.simulationDurationSeconds * 1_000_000.0;
    }

    private static void assertCountersClean() {
        long evaluations = CautiousWaitingPolicy.getEvaluationsTotal();
        long hardDenies = CautiousWaitingPolicy.getHardDenyFiredTotal();
        if (evaluations != 0 || hardDenies != 0) {
            throw new IllegalStateException(String.format(
                    "CautiousWaitingPolicy diagnostics were not reset before this run "
                            + "(evaluations=%d, hardDenyFired=%d). They are process-global statics with "
                            + "their own resetDiagnosticCounters(); Transaction.resetMetrics() does NOT "
                            + "clear them. Without the reset every run after the first reports a running "
                            + "total and the hard-deny rate appears to rise with run index.",
                    evaluations, hardDenies));
        }
    }

    /**
     * V3's pigeonhole rule in its analogue form: co-burst CPR offers against the reachable key
     * space {@code K = inDegreeNonzeroResources * instancePoolSize}. Below the limit, collisions
     * are MEASURED; above it they are forced by counting and the cell reports the size of the key
     * space rather than the mechanism. Emitted on every row whether or not it passes, as V3 does,
     * so a reader can apply a different threshold without a rerun.
     */
    private static double pigeonholeRatio(SimulatorParameters params, int instancePoolSize) {
        List<Model> pool = new ModelFactory(instancePoolSize).createModels(params.propertiesPerModel);
        CprSchema schema = new CprSchemaBuilder(pool, params.cprTopologyMode, params.semanticOverlap,
                params.avgCprsPerResource, params.maxCprsPerResource, params.semanticOverlapSeed,
                params.cprInitialAttractiveness, params.cprAcyclicTotalOrder).build();
        CprGraphMetrics metrics = CprGraphMetrics.from(schema, WorkloadAxisDefaults.TOTAL_PROPERTIES);
        long k = (long) metrics.inDegreeNonzeroResources() * instancePoolSize;
        if (k == 0) {
            return 0.0;
        }
        // Corrected offer rate - see WorkloadAxisDefaults.cprOffersPerBurst for why MPL*f*k
        // overstates it by ~4x here.
        double offers = WorkloadAxisDefaults.cprOffersPerBurst(params.initialTransactions,
                params.semanticOverlap, params.constantTransactionSize,
                params.readOnlyTransactionFraction);
        return offers / k;
    }

    private static void emit(PrintWriter out, Cell c) {
        out.printf(Locale.US, "%d,%d,%d,%d,%.4f,%d,%.6f,%.6f,%.6f,%.6f,%.6f,%d,%d,%.6f,%.2f,%s,%.2f,%.2f,%d,%s,%s%n",
                c.instancePool, c.d, c.totalLocks, (int) Math.ceil(c.d / (double) c.totalLocks),
                c.throughput, c.completions, c.fracBlocked, c.fracWaitingRoom, c.waitingRoomLockFraction,
                c.abortsPerCompletion, c.hardDenyRate(), c.hardDenyFired, c.evaluations,
                c.pigeonholeRatio, c.cuServiceToQuantum, c.terminatedBy, c.measurementWindowS,
                c.lockWaitP95, c.lockWaitSamples, c.interpretable(), c.inBand());
        out.flush();
    }

    private record Cell(int instancePool, int d, int totalLocks, double throughput, long completions,
            double fracBlocked, double fracWaitingRoom, double waitingRoomLockFraction, double abortsPerCompletion,
            long hardDenyFired, long evaluations, double pigeonholeRatio, double cuServiceToQuantum,
            String terminatedBy, double measurementWindowS,
            double lockWaitP95, int lockWaitSamples, double unitUtilisation,
            double lockConflictRate, long lockRequestsTotal) {

        /**
         * A cell with too few completions is not a measurement (never conclude from
         * {@code 1/throughput} below one completion). Every fraction here is a ratio whose
         * denominator is the completion count or the observation window, so at single-digit
         * completions they are noise wearing the shape of data - and the bisection would happily
         * steer on them.
         */
        static final long MIN_COMPLETIONS = 100;

        boolean interpretable() {
            return completions >= MIN_COMPLETIONS && "TIME_LIMIT".equals(terminatedBy);
        }

        boolean inBand() {
            return interpretable()
                    && fracBlocked >= TARGET_BLOCKED_LO && fracBlocked <= TARGET_BLOCKED_HI
                    && pigeonholeRatio < PIGEONHOLE_LIMIT;
        }

        double hardDenyRate() {
            return evaluations == 0 ? 0.0 : hardDenyFired / (double) evaluations;
        }

        @Override
        public String toString() {
            String flags = "";
            if (!"TIME_LIMIT".equals(terminatedBy)) {
                flags += "  *** " + terminatedBy + " ***";
            }
            if (completions < MIN_COMPLETIONS) {
                flags += "  *** ONLY " + completions + " COMPLETIONS - NOT A MEASUREMENT ***";
            }
            if (pigeonholeRatio >= PIGEONHOLE_LIMIT) {
                flags += "  *** PIGEONHOLE ***";
            }
            return String.format(Locale.US,
                    "pool=%-5d D=%-8d tps=%-8.2f n=%-6d win=%-8.0fs blocked=%.3f wr_lock=%.4f "
                            + "aborts/compl=%.4f hard_deny=%.4f (%d/%d) lw_p95=%.0f pigeonhole=%.4f%s",
                    instancePool, d, throughput, completions, measurementWindowS, fracBlocked,
                    waitingRoomLockFraction, abortsPerCompletion, hardDenyRate(), hardDenyFired, evaluations,
                    lockWaitP95, pigeonholeRatio, flags);
        }
    }
}
