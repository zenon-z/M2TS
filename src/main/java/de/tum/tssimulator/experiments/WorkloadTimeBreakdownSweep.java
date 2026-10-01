package de.tum.tssimulator.experiments;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import de.tum.tssimulator.bootstrap.ExperimentRuntimeSnapshot;
import de.tum.tssimulator.config.OracleClosurePlacement;
import de.tum.tssimulator.config.SimulatorParameters;
import de.tum.tssimulator.core.ModelFactory;
import de.tum.tssimulator.core.Property;
import de.tum.tssimulator.core.Transaction.LineagePhase;
import de.tum.tssimulator.cpr.CprSchemaBuilder;
import de.tum.tssimulator.experiments.support.ExperimentOutputPaths;
import de.tum.tssimulator.experiments.support.SeedSweepRunner;
import de.tum.tssimulator.experiments.support.WorkloadAxisDefaults;
import de.tum.tssimulator.experiments.support.WorkloadCellId;
import de.tum.tssimulator.locking.policy.LockingProtocol;

/**
 * Produces the per-transaction time-breakdown figure's data: one row per (cell, seed), carrying a
 * SIX-segment decomposition of a committed transaction's lifetime that closes exactly.
 *
 * <p>Layout follows Yu et al. (Abyss, VLDB '14) Fig 6 and Deneva (VLDB '17) Fig 8: one panel per
 * contention level, bars within a panel for the arms. Three panels at theta = 0 / 0.5 / 0.8; bars
 * are the no-CPR control, f = 0.05 / 0.25 / 0.80, and the concentrated topology alpha = 0.05.
 *
 * <h2>What the driver emits, and what the figure plots</h2>
 * Rows carry ABSOLUTE microseconds per completion for every segment; the figure normalises them to
 * fractions of transaction time (Abyss Fig 8-10 / Deneva Fig 8). Keeping the CSV absolute means the
 * Little's-law gate below is checkable from the data, which it would not be once normalised - a
 * normalised row sums to 1 whatever the estimator does.
 *
 * <h2>The estimator: time-weighted state counts</h2>
 * Each segment is {@code occupancy[P] / completions}, where {@code occupancy[P]} integrates the
 * live count of parents in phase P over the post-warm-up window. This is the textbook DES
 * measurement, and it replaced TWO per-lineage estimators that both failed the Little's-law gate
 * in opposite directions - averaging over completed lineages read 9.4x LOW (the stuck tail never
 * completes, so it never enters the average), and including in-flight lineages without clipping
 * over-counts warm-up. Both were patches on an approach whose edge cases - censoring, warm-up,
 * partial lifetimes - exist only because it reconstructs per-transaction histories at all.
 *
 * <pre>
 *   dispatch queue   PENDING_QUEUE     admitted-and-waiting-for-dispatch
 *   lock wait        ADMISSION_WAIT    blocked at the lock check (see below)
 *   backoff          BACKOFF           post-abort wait before the next attempt
 *   wasted work      derived           restarts/completion x service (see below)
 *   useful work      derived           realised footprint x per-resource cost
 *   processor wait   RUNNING remainder admitted and runnable, queued behind other work
 *   cascade wait     WAITING_ROOM      parent finished, waiting on its own CUs
 * </pre>
 *
 * <h2>Two gates, both external to the decomposition</h2>
 * <ul>
 *   <li><b>Equilibrium.</b> The parent population is pinned at the admission cap, so total
 *       population-time must equal {@code MPL x window}. Uses no throughput estimate, so it is the
 *       stronger of the two, and it is a HARD failure. It covers both leak directions: a double
 *       entry drifts the live count up, a double exit drifts it down, and either pulls this ratio
 *       off 1.0 as the drift accumulates.
 *   <li><b>Little's law.</b> {@code W = L / lambda}, restating the same thing per transaction in
 *       the figure's own units, so a reader can re-derive it from the throughput figure.
 * </ul>
 * Both read 1.000 on every cell. The per-snapshot population range is emitted too, which localises
 * a leak to the step where it happens. Only an OVERSHOOT is a fault: dipping below the cap is
 * refill lag between a completion and the next dispatch (measured [185, 192], worth 6e-6 of
 * population-time).
 *
 * <h2>Three things the obvious version of this figure gets wrong</h2>
 *
 * <p><b>1. "Lock wait" and "admission wait" are ONE segment here, not two.</b> Under C2PL preclaim
 * the whole footprint is acquired at admission or the transaction does not start, so there is no
 * in-flight lock wait to separate out. Labelled "lock wait (at admission)" so a reader who knows
 * Fig 6 does not look for the missing bar.
 *
 * <p><b>2. "Useful work" is mostly NOT work, and must be split.</b> RUNNING means admitted, which
 * at MPL 192 against 24 cores is largely queueing for a processor on the uncontended cells: on the
 * control at theta = 0 running holds ~79,300 ticks against a service of 20,020, so ~75% of what a
 * naive figure labels "useful work" is processor contention. Unlike the cascade case below, this
 * one splits CLEANLY by computation: a parent is a single object on a single processor, so its
 * service is exactly {@code n x (io + cpu + lockIo + lockCpu)}. {@code n} is taken REALISED
 * (completed-group parent references / completions), not assumed to be k, because footprint dedup
 * can put it below k. Report achieved rather than configured.
 *
 * <p><b>2a. The caption must say processor wait is the OPERATING POINT, not a finding.</b> The
 * control spends ~74% of its lifetime queueing for a processor, and a reader coming from Abyss -
 * where the machine is fed but not oversubscribed - will read that as a mistake in the setup and
 * discount the figure. It is deliberate: MPL 192 against 24 cores is 8x oversubscription, chosen
 * so the no-CPR reference stays CAPACITY-BOUND at every x, which is what makes every ratio in the
 * campaign a measurement against a fixed denominator rather than a quotient of two noisy numbers.
 * One clause pre-empts it:
 *
 * <blockquote>"Processor wait reflects 8x oversubscription (MPL 192, 24 cores), chosen so the
 * no-CPR reference is capacity-bound at every x."</blockquote>
 *
 * The CPR result is then read as what CHANGES between bars - cascade wait and lock wait growing,
 * processor wait SHRINKING as parents park in the waiting room and stop competing for processors -
 * not as the absolute height of any one segment.
 *
 * <p><b>3. Cascade WORK is not computed, and the waiting room is reported whole.</b> The tempting
 * move is {@code cpr_edges_fired_per_completion x service} subtracted from the waiting room. It is
 * invalid: with {@code groupUnitAffinity = false} a group's CUs run CONCURRENTLY on different
 * processors, so summed CU service is not wall-clock on the parent's critical path. Subtracting it
 * would overstate cascade work, understate cascade delay, and can go negative. The cascade's work
 * is already quantified - that is what {@code sdr} is in the multiplier figure - so this figure
 * reports cascade DELAY and does not restate it. Measuring the split properly means giving
 * bookkeepers their own lineage accumulators; they have none today, by design
 * ({@code markPhase} is guarded {@code !(txn instanceof BookkeeperTransaction)} at every call site).
 *
 * <h2>Wasted work, recovered without per-transaction history</h2>
 * {@code RUNNING} holds the time of EVERY attempt, including ones that later aborted, while
 * service is one attempt's work - so calling the remainder "processor wait" would book
 * re-executed service as queueing, and at high skew that is most of it. Under C2PL the parent
 * preclaims its whole footprint and finishes its service before any CU fires, and a group abort
 * originates in a CU's hard deny, so an aborting attempt has ALWAYS completed its full service
 * first. Each restart therefore wastes exactly one service:
 * {@code wasted = restarts/completion x service}, and
 * {@code processorWait = running - service - wasted}.
 *
 * <p>The premise is checked rather than assumed: if a restart wasted less than a full service,
 * {@code wasted} would be over-charged and processor wait would go negative. It must stay >= 0 in
 * every cell, and service must NOT be clamped with {@code Math.min(service, running)}: a guard
 * whose input is clamped into its passing range is not a guard.
 *
 * <h2>Same run shape as the campaign, so the two figures describe the same runs</h2>
 * Horizon, warm-up, pool, MPL, cores and per-cell salts are the campaign's
 * ({@link WorkloadAxisDefaults}, {@link WorkloadCellId}), not this tool's own. That buys a free
 * cross-check: a row's {@code throughput_tps} must equal the campaign's tps for the same cell and
 * seed BIT FOR BIT. Where the cell coordinates coincide with an axis cell, they do.
 *
 * <p>Sharded exactly like {@link WorkloadAxisSweep} ({@code -Dts.tb.shards} / {@code -Dts.tb.shard}),
 * so the same server script runs it unchanged.
 *
 * <h2>Second experiment on the same machinery: SS2PL triggered vs inlined CPRs</h2>
 * {@code -Dts.tb.ss2pl=0,0.05,0.25,0.80} replaces the six campaign arms with an SS2PL
 * triggered/inlined pair per f (plus the no-CPR control at f = 0) - see {@link #ss2plArms}. It is
 * here rather than in its own driver because the two GATES are the reason to run it here at all:
 * SS2PL has never been exercised end to end in this harness, and an equilibrium check that reads
 * 1.000 is the cheapest available evidence that the phase accounting survives hold-and-wait.
 *
 * <p><b>Only throughput and the gates are readable on those rows.</b> The segment split assumes an
 * aborting attempt completed its full service first, which C2PL preclaim guarantees and S2PL does
 * not - so the rows carry the segment columns but the CSV's own header block marks them unvalidated
 * under S2PL. That is stated in the file, not only here.
 */
public final class WorkloadTimeBreakdownSweep {

    private static final double[] THETAS = {0.0, 0.5, 0.8};

    /**
     * One bar. {@code f = 0} is the control, where alpha is inert (no links to attach).
     *
     * <p>{@code protocol} and {@code preclaim} carry the CPR-execution arm: the campaign's six arms
     * are all {@code (C2PL, false)} - runtime-triggered CPRs under preclaim - and the SS2PL
     * triggered/inlined comparison (see {@link #ss2plArms}) varies exactly these two.
     */
    private record Arm(String label, double f, double alpha, LockingProtocol protocol,
            boolean preclaim, OracleClosurePlacement placement) {
        /** The campaign's shape: C2PL, CPRs triggered at runtime as CUs. */
        Arm(String label, double f, double alpha) {
            this(label, f, alpha, LockingProtocol.C2PL, false, OracleClosurePlacement.AT_END);
        }
    }

    /**
     * The five f-and-alpha arms, plus the control.
     *
     * <p><b>THE CONCENTRATION SERIES IS alpha = 0.05 / 1.0 / 100, and its MIDPOINT IS THE
     * {@code f=0.25} ARM.</b> {@code BASE_ALPHA} is 1.0, so every arm that does not name an alpha
     * runs at 1.0 - which means an {@code alpha=1.0} arm would be a bit-identical duplicate of the
     * {@code f=0.25} bar already in the figure, at the cost of 15 runs and a sixth bar saying
     * nothing new. Stated here because the collision is invisible from the arm labels: one names
     * its f and the other names its alpha, and they are the same cell.
     *
     * <p><b>Direction, because it is the opposite of the intuitive reading.</b> Price attachment
     * draws targets with {@code P ~ inDegree + alpha}, so SMALL alpha lets rich-get-richer
     * dominate and CONCENTRATES in-degree; LARGE alpha washes that out and SPREADS it. So the
     * spread end is alpha = 100, not alpha = 1.0, and 100 is what {@code PANEL_D_ALPHA} carries as
     * its high value. Panel D's subcaption states the same direction for the same reason.
     *
     * <p>Held at {@code BASE_SEMANTIC_OVERLAP} like the alpha=0.05 arm, so the three alpha bars
     * differ in alpha ALONE and the comparison is against a fixed f.
     */
    private static final List<Arm> ARMS = List.of(
            new Arm("control", 0.0, WorkloadAxisDefaults.BASE_ALPHA),
            new Arm("f=0.05", 0.05, WorkloadAxisDefaults.BASE_ALPHA),
            new Arm("f=0.25", 0.25, WorkloadAxisDefaults.BASE_ALPHA),
            new Arm("f=0.80", 0.80, WorkloadAxisDefaults.BASE_ALPHA),
            new Arm("alpha=0.05", WorkloadAxisDefaults.BASE_SEMANTIC_OVERLAP, 0.05),
            new Arm("alpha=100", WorkloadAxisDefaults.BASE_SEMANTIC_OVERLAP, 100.0));

    private WorkloadTimeBreakdownSweep() {
    }

    /**
     * The SS2PL triggered-vs-inlined arms, one pair per {@code f} in {@code spec}
     * ({@code -Dts.tb.ss2pl=0,0.05,0.25,0.80}).
     *
     * <p>Both arms are {@link LockingProtocol#S2PL} and differ in ONE field,
     * {@code oracleClosurePreclaim}:
     *
     * <ul>
     *   <li><b>triggered</b> - the parent carries its own k resources; the CPR closure arrives at
     *       runtime as CUs spawned by {@code SchemaCprFactory} after the parent completes.</li>
     *   <li><b>inlined</b> - {@code TransactionFactory.expandToOracleClosure} puts the same closure
     *       into the parent's own operation list (after its own k operations - see
     *       {@code OracleClosureOperationOrderTest}), and
     *       {@code BookkeeperCreationCoordinator.filterAlreadyCoveredResources} then prunes every
     *       CU as already covered, so nothing is triggered at all.</li>
     * </ul>
     *
     * <p><b>{@code f = 0} is ONE arm, not two.</b> With no CPR links there is no closure to inline
     * and no CU to trigger, so {@code preclaim} is inert and the two would be bit-identical runs
     * under different labels - which reads as a measured null result rather than as a definitional
     * one. Emitted as {@code ss2pl-control}, the no-CPR reference the other bars are read against.
     *
     * <p><b>f IS IN THE LABEL.</b> The cell key is {@code (theta, arm)} and the arm label is what
     * the progress line, the {@code arm} column and {@code -Dts.tb.arms} all match on. Two f values
     * in one invocation would otherwise produce two cells indistinguishable by label, and an
     * {@code onlyArms} filter would silently select both.
     */
    private static List<Arm> ss2plArms(String spec, int opBatch) {
        List<Arm> built = new ArrayList<>();
        for (String tok : spec.split(",")) {
            double f = Double.parseDouble(tok.trim());
            if (!(f >= 0.0) || f > 1.0) {
                throw new IllegalArgumentException(
                        "ts.tb.ss2pl takes semanticOverlap values in [0, 1]; got " + f);
            }
            if (f == 0.0) {
                built.add(new Arm("ss2pl-control", 0.0, WorkloadAxisDefaults.BASE_ALPHA,
                        LockingProtocol.S2PL, false, OracleClosurePlacement.AT_END));
                continue;
            }
            String label = String.format(Locale.US, "%.2f", f);
            built.add(new Arm("ss2pl-triggered-f" + label, f, WorkloadAxisDefaults.BASE_ALPHA,
                    LockingProtocol.S2PL, false, OracleClosurePlacement.AT_END));
            built.add(new Arm("ss2pl-inlined-at-end-f" + label, f, WorkloadAxisDefaults.BASE_ALPHA,
                    LockingProtocol.S2PL, true, OracleClosurePlacement.AT_END));
            // INTERLEAVED ONLY AT b > 0. At b = 0 the whole footprint is taken in one call, so the
            // two placements are the same run under two labels - which would read as a measured null
            // result rather than as a definitional one, and would double the cost of a b = 0 sweep
            // for nothing. Refused rather than silently skipped would be worse: a sweep spec that is
            // legitimate at b = 1 should not fail at b = 0.
            if (opBatch > 0) {
                built.add(new Arm("ss2pl-inlined-interleaved-f" + label, f,
                        WorkloadAxisDefaults.BASE_ALPHA, LockingProtocol.S2PL, true,
                        OracleClosurePlacement.INTERLEAVED));
            }
        }
        return built;
    }

    /**
     * Group-unit affinity must be provably off, for the reason {@link #ss2plArms} exists.
     *
     * <p>Affinity pins a group's members to one IO and one CPU unit. The INLINED arm has a
     * single-member group, so there is nothing to pin and the flag cannot touch it; the TRIGGERED
     * arm pays it on every CU. It is therefore a one-sided tax on exactly the comparison these arms
     * make - measured at ~1.5x on the dynamic arm's throughput elsewhere in this project - and a
     * triggered-vs-inlined ratio taken with it on is partly a scheduling artifact.
     *
     * <p>{@link WorkloadAxisDefaults#baseBuilder} already sets it false and the value is emitted as
     * a column, but neither stops a future caller from overriding it between the builder and here.
     * Asserted rather than assumed, and asserted on the params the run actually uses.
     */
    private static void assertAffinityOff(SimulatorParameters params) {
        if (params.groupUnitAffinity) {
            throw new IllegalStateException(
                    "groupUnitAffinity is ON. It pins a group's members to one unit, which the "
                    + "inlined (single-member) arm cannot pay and the triggered arm pays on every "
                    + "CU - so the triggered-vs-inlined ratio would carry a one-sided scheduling "
                    + "tax. Run with WorkloadAxisDefaults.BASE_AFFINITY = false.");
        }
    }

    public static void main(String[] args) throws IOException {
        int seeds = Integer.getInteger("ts.tb.seeds", 5);
        if (seeds < 1) {
            throw new IllegalArgumentException("ts.tb.seeds must be >= 1; got " + seeds);
        }
        // BACKOFF SENSITIVITY. The figure shows backoff at 92% of lifetime on
        // theta=0.8/f=0.80, and backoff is a POLICY constant rather than a workload property, so
        // a reviewer can reasonably ask whether the high-skew collapse is an artifact of the
        // retry schedule. -Dts.tb.backoffScale=0.5|1|2 answers it.
        //
        // The scale is deliberately NOT part of the cell salt (see runCell), so the three scales
        // draw the SAME five seeds and the comparison is paired rather than a difference of two
        // independently-noisy means - which matters at f=0.80, where the seed sd is ~20% of the
        // mean.
        double backoffScale = Double.parseDouble(System.getProperty("ts.tb.backoffScale", "1.0"));
        if (!(backoffScale > 0.0) || Double.isNaN(backoffScale)) {
            throw new IllegalArgumentException("ts.tb.backoffScale must be > 0; got " + backoffScale);
        }
        // Cell filters, so a sensitivity run costs two cells rather than fifteen. Absent, both
        // default to the full sweep.
        // TRANSACTION LENGTH, so the breakdown can be taken at more than one point of the
        // txnlen axis rather than only at the base k = 10.
        //
        // Restricted to values the AXIS actually sweeps ({5, 10, 25, 50}). A breakdown at some
        // other k would be a bar chart with no point on the throughput curve to sit beside, and
        // the whole purpose here is to explain a feature of that curve.
        int txnLen = Integer.getInteger("ts.tb.txnlen", WorkloadAxisDefaults.TRANSACTION_LENGTH);
        boolean onAxis = false;
        for (double v : WorkloadAxisSweep.axisValues("txnlen")) {
            onAxis |= (int) v == txnLen;
        }
        if (!onAxis) {
            throw new IllegalArgumentException(String.format(Locale.US,
                    "-Dts.tb.txnlen=%d is not a point on the txnlen axis %s - a breakdown there "
                    + "could not be read against the throughput figure", txnLen,
                    Arrays.toString(WorkloadAxisSweep.axisValues("txnlen"))));
        }
        // CORE COUNT, so the breakdown can be taken along the cores axis. Same motivation as
        // ts.tb.txnlen above, for a sharper question: the cores figure shows the f = 0.80 arm
        // scaling SUPERLINEARLY from 1 to 4 cores, and the decomposition of that into
        // utilisation x per-busy-second efficiency (scripts/plotting/plot_cores_mechanism.py)
        // puts the whole gain in utilisation - the machine is 83% IDLE at one core. Why it is
        // idle cannot be read off the axis sweep's two occupancy columns, because frac_blocked
        // and frac_waiting_room cover only ~87-91% of the time and the remainder mixes RUNNING
        // with BACKOFF. This sweep's five-phase partition separates them, and it is the only
        // instrument in the campaign that does.
        //
        // MPL IS NOT TOUCHED. WorkloadAxisSweep holds it at 192 across its own cores axis on
        // purpose - tying it to cores would sweep offered concurrency and service capacity
        // together - and a breakdown run at a different MPL could not be read against that
        // figure at all. Only the processor counts move, exactly as WorkloadAxisSweep.build()
        // does it for axis "cores".
        //
        // Restricted to values the AXIS actually sweeps, for the txnlen knob's reason: a
        // breakdown at some other core count has no point on the throughput curve to sit beside.
        int cores = Integer.getInteger("ts.tb.cores", WorkloadAxisDefaults.CORES);
        boolean coresOnAxis = false;
        for (double v : WorkloadAxisSweep.axisValues("cores")) {
            coresOnAxis |= (int) v == cores;
        }
        if (!coresOnAxis) {
            throw new IllegalArgumentException(String.format(Locale.US,
                    "-Dts.tb.cores=%d is not a point on the cores axis %s - a breakdown there "
                    + "could not be read against the throughput figure", cores,
                    Arrays.toString(WorkloadAxisSweep.axisValues("cores"))));
        }
        // ALPHA SWEEP. -Dts.tb.alphas=0.01,0.005,... replaces the arm list with one arm per
        // alpha, all at the base f, so only concentration varies.
        //
        // Alpha weights the target draw as (inDegree + alpha) over the CANDIDATE PROPERTY pool -
        // 200 here, not the 512,200 resources - so the scale that matters is 1/200 = 0.005, where
        // alpha stops competing with a single accumulated in-edge.
        String alphaSpec = System.getProperty("ts.tb.alphas");
        // SS2PL TRIGGERED-VS-INLINED. Replaces the arm list, the way ts.tb.alphas does, because it
        // is a different experiment on the same machinery rather than extra bars on this figure:
        // both arms run S2PL where the campaign's six run C2PL, so pooling them would mix two
        // acquisition protocols into one panel.
        String ss2plSpec = System.getProperty("ts.tb.ss2pl");
        if (alphaSpec != null && ss2plSpec != null) {
            throw new IllegalArgumentException(
                    "ts.tb.alphas and ts.tb.ss2pl both replace the arm list; pick one");
        }
        // OPERATION BATCH SIZE, and 0 is not a neutral default - it is a MODELLING CHOICE that the
        // triggered/inlined comparison rests on.
        //
        // At 0 the whole footprint is taken in one acquireAllLocks() call, so the inlined arm's
        // parent takes all ~|closure| locks atomically before it executes, while the triggered
        // arm's parent takes k atomically and each CU takes its own later. The two arms then differ
        // in acquisition GRANULARITY as well as in when the closure is discovered. At 1 the S2PL
        // cursor walks the operation list one at a time, which is what makes the inlined parent
        // genuinely claim-as-needed and the comparison a test of dynamism alone.
        //
        // Exposed as a knob rather than pinned, and named in the filename when set, because rungs
        // 1-3 of the SS2PL ladder (b > 0) have never been run end to end - so 0 is the only value
        // with a validated reference behind it today.
        int opBatch = Integer.getInteger("ts.tb.opbatch", 0);
        if (opBatch < 0) {
            throw new IllegalArgumentException("ts.tb.opbatch must be >= 0; got " + opBatch);
        }
        String onlyArms = System.getProperty("ts.tb.arms");
        String onlyThetas = System.getProperty("ts.tb.thetas");
        // The output file must NAME the scale. Three runs at 0.5/1/2 that all write
        // wl_breakdown_raw.csv are three configurations indistinguishable in the output. A
        // backoff_scale COLUMN is added below as well, so the row survives being concatenated
        // with its siblings.
        // THE FILTER GOES IN THE NAME TOO, not just the backoff scale. At scale 1.0 a
        // one-cell, one-seed probe otherwise writes wl_breakdown_raw.csv - the
        // UNSUFFIXED name the plot script prefers over the fifteen shard files - and would have
        // silently replaced a 75-row figure with a single control row. The scale alone is not
        // enough to identify a run, because a filtered sweep at the default scale is still not
        // the campaign's sweep. Any deviation from the full grid must be visible in the filename.
        //
        // EXACTLY ONE run writes the untagged name: the full grid at the default scale, which is
        // the campaign's own sweep. Everything else is tagged, INCLUDING scale 1.0 once a filter
        // is set - otherwise a five-scale sensitivity sweep produces four self-describing
        // filenames and one that is silent about being the control, and whoever concatenates them
        // later has to know that the quiet one means 1.0. The backoff_scale column would still
        // say so, but a filename that has to be decoded by opening the file is not an identifier.
        // txnLen AND cores BELONG IN THIS PREDICATE. Reading only the scale and the two cell
        // filters makes -Dts.tb.txnlen=25 alone - default scale, no filter - produce tag = "" and
        // write wl_breakdown_raw.csv, the untagged name the plot script PREFERS over the shard
        // files. A k = 25 run would then silently replace the campaign's k = 10 figure data,
        // which is the failure the comment above this block describes for the
        // filter case ("Any deviation from the full grid must be visible in the filename") and
        // that the _k suffix in the tag body was already written to prevent. The suffix could
        // never fire, because the branch that emits it was unreachable for a txnlen-only run.
        //
        // Fixed rather than worked around, because adding cores the same way would reproduce it:
        // the tag body's _c suffix would be just as unreachable. Both knobs now gate the
        // predicate, so exactly one configuration writes the untagged name - the full grid at
        // base k, base cores, scale 1.0, which is the campaign's own sweep.
        // EXPLICITLY SET COUNTS AS A DEVIATION, even when the value equals the default.
        //
        // The cores investigation needs a 24-core reference point, and -Dts.tb.cores=24 produces
        // rows for a cell the CAMPAIGN already has - same cell_id, same salted seed, bit-identical
        // throughput. But the binary changed underneath: LineagePhase.BLOCKED moved blocked time
        // out of processor_wait_ticks. Two files then hold the same (cell, seed) with different
        // attributions, and plot_time_breakdown.py must refuse to pool them - which would also
        // block the published figure from rendering at all.
        //
        // Naming the file after the knob that was TOUCHED rather than the value it landed on
        // keeps the reference run in its own file, so the campaign's rows stay loadable and the
        // investigation's rows stay separate. Integer.getInteger cannot distinguish "set to 24"
        // from "defaulted to 24"; getProperty can.
        boolean coresExplicit = System.getProperty("ts.tb.cores") != null;
        String diagLabel = System.getProperty("ts.tb.diag");
        // LOCK POOL WIDTH, decoupled from the core count. Absent = the historical
        // min(numIo, numCpu). See DefaultExecutionBundle's lockPoolWidth.
        Integer lockPool = Integer.getInteger("ts.tb.lockpool");
        if (lockPool != null && lockPool < 1) {
            throw new IllegalArgumentException("ts.tb.lockpool must be >= 1; got " + lockPool);
        }
        // GROUP UNIT AFFINITY, opt-in. Off is the campaign default and the neutral choice between
        // arms (see assertAffinityOff). ON is a deliberate arm: it serialises a group's members
        // onto one IO and one CPU unit, which is what triggered CPRs look like when they run in the
        // originating session rather than as independent units. Only meaningful for the triggered
        // arm - an inlined arm has a single-member group and nothing to pin.
        boolean affinity = Boolean.getBoolean("ts.tb.affinity");
        if (diagLabel != null && diagLabel.isBlank()) {
            throw new IllegalArgumentException("ts.tb.diag needs a label, e.g. -Dts.tb.diag=c2plpair");
        }
        // ss2plSpec AND opBatch GATE THE PREDICATE, for the reason txnLen and cores do: an SS2PL
        // run is a different protocol under the same column names, and plot_time_breakdown.py
        // globs wl_breakdown*_raw.csv. Without this, `-Dts.tb.ss2pl=...` alone would write the
        // untagged name the plot script PREFERS over the shard files and replace the campaign's
        // C2PL figure data with S2PL rows. (The script also filters non-C2PL rows by VALUE now,
        // which is the defence that cannot be broken by renaming a file; this is the one that keeps
        // the two experiments in separate files at all.)
        boolean isCampaignSweep = backoffScale == 1.0 && onlyArms == null && onlyThetas == null
                && txnLen == WorkloadAxisDefaults.TRANSACTION_LENGTH
                && !coresExplicit && ss2plSpec == null && opBatch == 0 && diagLabel == null
                && lockPool == null && !affinity;
        String tag = isCampaignSweep ? ""
                : String.format(Locale.US, "_bs%.2f", backoffScale)
                + (onlyArms == null ? "" : "_arms" + onlyArms.replaceAll("[^A-Za-z0-9.]", ""))
                + (onlyThetas == null ? "" : "_th" + onlyThetas.replaceAll("[^A-Za-z0-9.]", ""))
                + (txnLen == WorkloadAxisDefaults.TRANSACTION_LENGTH ? "" : "_k" + txnLen)
                + (coresExplicit ? "_c" + cores : "")
                // Its own tag, so a continuum of alphas never lands in a file the breakdown or
                // contention figures read - those plot six NAMED arms and would silently gain bars.
                // THE ALPHA VALUE, NOT JUST "_alphasweep".
                //
                // A bare "_alphasweep" is the same path for every alpha, and the natural way to
                // run this sweep is one JVM per alpha - so seven processes opened the same file
                // and interleaved their writes. The result was not an overwrite but a CORRUPTED
                // csv: a bare "T" appeared mid-file where one process's line was cut by another's.
                //
                // Worth stating why the usual protections missed it. The arm filter normally puts
                // a distinguishing "_arms<label>" in the name, but -Dts.tb.alphas sets no arm
                // filter, so that tag was empty. And the alphas are all at the same f, theta, k
                // and cores, so every other tag component matched too. Nothing in the sweep
                // validates that two concurrent invocations resolve to different paths, so the
                // only defence is that the tag actually names what varies.
                + (alphaSpec == null ? ""
                   : "_alpha" + alphaSpec.replaceAll("[^A-Za-z0-9.]", "_"))
                // The f LIST, not a bare "_ss2pl", for the alpha sweep's reason: the natural way to
                // extend this is one JVM per f, and two of those resolving to the same path
                // interleave their writes into a corrupted csv rather than overwriting cleanly.
                + (ss2plSpec == null ? ""
                   : "_ss2pl" + ss2plSpec.replaceAll("[^A-Za-z0-9.]", "_"))
                + (opBatch == 0 ? "" : "_b" + opBatch)
                // DIAGNOSTIC LABEL. A probe run that reproduces an existing cell to compare
                // against it resolves to the same filename as that cell, and has overwritten a
                // committed archive file that way. Naming the run as a probe keeps it in its own
                // file; the overwrite guard below is the backstop for when someone forgets.
                + (lockPool == null ? "" : "_lp" + lockPool)
                + (affinity ? "_aff" : "")
                + (diagLabel == null ? ""
                   : "_diag" + diagLabel.replaceAll("[^A-Za-z0-9.]", "_"));
        int shardCount = Integer.getInteger("ts.tb.shards", 1);
        int shardIndex = Integer.getInteger("ts.tb.shard", 0);
        if (shardCount < 1 || shardIndex < 0 || shardIndex >= shardCount) {
            throw new IllegalArgumentException(String.format(Locale.US,
                    "bad shard %d of %d (expected 0 <= shard < shards)", shardIndex, shardCount));
        }
        String suffix = (shardCount == 1 ? "" : "_shard" + shardIndex) + tag;
        String path = ExperimentOutputPaths.resultsFile("wl_breakdown" + suffix + "_raw.csv");
        // REFUSES TO OVERWRITE, because the tag scheme alone does not protect anything.
        //
        // Every guard above this point is about making a deviation VISIBLE in the filename. None of
        // them stops a run whose filename is correct from destroying an identically-named file
        // that already exists - and the results directory holds the extracted campaign archive,
        // whose files are the provenance for published figures. This has happened: a one-seed
        // C2PL probe of the f=0.25/theta=0.5 cell resolved to exactly the archived name for that
        // cell and replaced 5 archived seeds with 1, recoverable only because the archive is
        // committed and tracked by sha256.
        //
        // The escape hatches are named in the message rather than left to be discovered, because a
        // guard that blocks legitimate work without saying how to proceed gets deleted.
        if (Files.exists(Path.of(path))
                && !Boolean.getBoolean("ts.tb.overwrite")) {
            throw new IllegalStateException(String.format(Locale.US,
                    "%s already exists and this sweep will not overwrite it.%n"
                    + "  - probing an existing cell?  add -Dts.tb.diag=<label> (own filename), or%n"
                    + "    send the whole run elsewhere with -Dts.experiments.outputDir=<dir>%n"
                    + "  - deliberately regenerating this exact file?  -Dts.tb.overwrite=true%n"
                    + "Refusing rather than appending: two writers on one path interleave into a "
                    + "corrupted csv rather than failing, which has already cost 35 rows of this "
                    + "sweep.", path));
        }

        List<Arm> arms = ARMS;
        if (alphaSpec != null) {
            List<Arm> built = new ArrayList<>();
            for (String tok : alphaSpec.split(",")) {
                double av = Double.parseDouble(tok.trim());
                if (!(av > 0.0)) {
                    throw new IllegalArgumentException(
                            "alpha must be > 0 (got " + av + "): every in-degree starts at 0, so "
                            + "alpha = 0 leaves every candidate at weight 0 and nothing is drawable");
                }
                built.add(new Arm(String.format(Locale.US, "alpha=%g", av),
                        WorkloadAxisDefaults.BASE_SEMANTIC_OVERLAP, av));
            }
            arms = built;
        } else if (ss2plSpec != null) {
            arms = ss2plArms(ss2plSpec, opBatch);
        }

        List<Object[]> cells = new ArrayList<>();
        for (double theta : THETAS) {
            if (onlyThetas != null && !matches(onlyThetas, String.format(Locale.US, "%.1f", theta))) {
                continue;
            }
            for (Arm arm : arms) {
                if (onlyArms != null && !matches(onlyArms, arm.label())) {
                    continue;
                }
                cells.add(new Object[] {theta, arm});
            }
        }
        if (cells.isEmpty()) {
            // An empty sweep writes a header-only CSV and exits 0, which reads as "ran, found
            // nothing" rather than "your filter matched nothing" - a zero indistinguishable from
            // an absence, applied to a cell list.
            throw new IllegalArgumentException(String.format(Locale.US,
                    "no cells match ts.tb.arms=%s / ts.tb.thetas=%s", onlyArms, onlyThetas));
        }
        System.out.printf(Locale.US,
                "time-breakdown sweep: %d cells x %d seeds, horizon %ds warmup %ds, pool %d MPL %d%n",
                cells.size(), seeds, WorkloadAxisDefaults.SIMULATION_SECONDS,
                WorkloadAxisDefaults.WARMUP_SECONDS, WorkloadAxisDefaults.INSTANCE_POOL_SIZE,
                WorkloadAxisDefaults.MPL);

        try (PrintWriter out = new PrintWriter(new FileWriter(path))) {
            // THE DENOMINATOR, IN THE FILE. Every per-transaction quantity here - throughput_tps
            // included - is per LOGICAL transaction, i.e. per root-parent completion. That is one
            // GROUP in the triggered arm (parent + however many CUs its cascade spawned) and one
            // TRANSACTION in the inlined arm (the closure is inside the parent, so the group has a
            // single member). It is deliberately NOT per member: the two arms reach the same
            // resources, and counting members would make the inlined arm's throughput higher by
            // definition rather than by measurement.
            //
            // Written as a leading '#' block because the reader that needs it a year from now opens
            // the CSV, not this class. Every consumer under scripts/plotting skips '#' lines.
            out.println("# per-transaction unit: ONE root-parent completion = one GROUP (triggered "
                    + "arm: parent + its CU cascade) = one TRANSACTION (inlined arm: closure inside "
                    + "the parent, single-member group). Not per group member.");
            out.println("# ticks are microseconds; segment columns are absolute, not normalised.");
            // STATED IN THE FILE, because the columns exist and look readable. The wasted/service/
            // processor-wait split assumes an aborting attempt has always completed its full
            // service first, which is true under C2PL preclaim (a parent finishes before any CU can
            // hard-deny) and NOT established under S2PL, where a parent can be denied mid-footprint.
            // Under S2PL read throughput_tps, completions and the two gates; treat the segment
            // columns as unvalidated. A negative processor_wait_ticks is that premise failing, and
            // is left unclamped so it shows.
            out.println("# segment split (wasted/service/processor_wait) is VALIDATED FOR C2PL "
                    + "ONLY. On locking_protocol=S2PL rows read throughput_tps, completions, "
                    + "population_ratio and littles_ratio; the segments need rework first.");
            out.println("# locking_protocol / oracle_preclaim / group_unit_affinity / "
                    + "operation_batch_size are read off the params the run used, not off the arm "
                    + "label. Filter on them before pooling rows from different files.");
            out.println("cell_id,theta,arm,f,alpha,seed,throughput_tps,completions,"
                    // cores AS A COLUMN, not only in the filename. The rows from a cores sweep
                    // get concatenated across six runs, and at that point the filename is gone -
                    // the same reason backoff_scale is a column beside its _bs tag.
                    + "window_ticks,mpl,cores,pop_min,pop_max,"
                    + "mean_lifetime_ticks,queue_ticks,lock_wait_ticks,backoff_ticks,"
                    + "wasted_ticks,service_ticks,processor_wait_ticks,cascade_wait_ticks,"
                    // APPENDED, like the enum constant, so a reader diffing an archived CSV
                    // against a new one sees one added column rather than a reordering.
                    + "blocked_running_ticks,blocked_waiting_room_ticks,blocked_other_ticks,"
                    + "mean_parent_resource_refs,backoff_scale,"
                    // Measured on the graph THIS ROW's run built - the schema is rebuilt from the
                    // same params, semanticOverlapSeed included - rather than from a
                    // convenience seed, which would describe a graph no run used.
                    //
                    // DISTINCT TARGETS is the concentration axis rather than a top-k share: a
                    // top-10 share is already 100% at alpha = 0.01, so every smaller alpha would
                    // plot at x = 1.0 and the sweep would show nothing.
                    //
                    // MAX OUT-DEGREE is beside it because it IS the floor - targets are drawn
                    // without replacement per source, so a source of out-degree d needs d distinct
                    // targets. Measured: distinct >= maxOut in every graph, with one seed exactly
                    // on it at alpha = 0.0005. Without this column the floor reads as the sampler
                    // giving up rather than as a structural bound.
                    + "cpr_distinct_targets,cpr_max_in_degree,cpr_max_target_share,"
                    + "cpr_max_out_degree,cpr_edges,"
                    // Two gates, both of which must read 1.000. population_ratio is the
                    // stronger: it compares total population-time against MPL x window and
                    // involves no throughput estimate. littles_ratio restates it per transaction.
                    + "population_ratio,littles_ratio,"
                    + "terminated_by,"
                    // APPENDED AT THE END, like blocked_*_ticks before them, so an archived CSV
                    // diffs as added columns rather than a reordering. The first four are read off
                    // the run's own params in writeRow - a column that restated the arm label could
                    // not disagree with it, and therefore could not catch a wiring mistake.
                    + "locking_protocol,oracle_preclaim,group_unit_affinity,operation_batch_size,"
                    // WORK CONSERVATION, and it is what makes the triggered/inlined throughput
                    // ratio interpretable rather than merely true.
                    //
                    // mean_parent_resource_refs counts the ROOT PARENT's resources only, so on its
                    // own it cannot distinguish "the inlined arm does the same work in one
                    // transaction" from "the inlined arm does more work". This is its CU-side
                    // counterpart, over the same completed-group denominator: the triggered arm
                    // should put the closure here and ~k in the parent column, the inlined arm ~0
                    // here and k+closure in the parent column, and the two arms' TOTALS should
                    // agree. If they do not, the two arms are not executing the same CPR
                    // operations and no ratio between them is a protocol result.
                    + "mean_cpr_resource_refs,mean_total_resource_refs,"
                    // WHERE the CPR operations are, as one value, so a reader does not have to
                    // reconstruct it from oracle_preclaim plus a placement enum plus the arm label.
                    // TRIGGERED = separate CUs at runtime; AT_END / INTERLEAVED = inlined into the
                    // parent, closure after all its own operations / each target after its source.
                    + "cpr_placement,"
                    // The PRIMITIVE behind wasted_ticks (wasted = restarts/completion x service).
                    // Emitted because wasted_ticks inherits the C2PL premise that an aborting
                    // attempt completed its full service first, which S2PL does not guarantee -
                    // this column carries no such assumption and is the one to compare across arms.
                    + "restarts_per_completion,"
                    // LOCK POOL WIDTH and the realised affinity, both read off the run.
                    + "lock_pool_size,"
                    // DISCARDED SERVICE AT ABORT - the measured answer to "how much work does one
                    // abort throw away", which restarts_per_completion (frequency) and wasted_ticks
                    // (restarts x service, i.e. the assumption) cannot give.
                    + "aborted_parent_attempts,aborted_cu_attempts,"
                    + "discarded_parent_service_per_abort,discarded_cu_service_per_abort,"
                    + "discarded_service_per_completion,parent_ops_reached_per_abort");
            // FLUSHED IMMEDIATELY, so a run that is killed leaves a file that says what it was
            // going to be. PrintWriter buffers, and the first row's own flush() is the only one
            // before it - so a sweep interrupted during its FIRST cell (the 1-core cells run for
            // tens of minutes) wrote a 0-byte file, which is indistinguishable from "the driver
            // never started" and sends the reader to the wrong question entirely.
            out.flush();
            for (int position = 0; position < cells.size(); position++) {
                if (position % shardCount != shardIndex) {
                    continue;
                }
                double theta = (Double) cells.get(position)[0];
                Arm arm = (Arm) cells.get(position)[1];
                System.out.printf(Locale.US, "  [%d/%d] theta=%.2f %s%n",
                        position + 1, cells.size(), theta, arm.label());
                runCell(out, theta, arm, seeds, backoffScale, txnLen, cores,
                        alphaSpec != null, opBatch, lockPool, affinity);
            }
        }
        System.out.println("wrote " + path);
    }

    /** Exact-match filter over a comma-separated list; no globbing, so a typo fails loudly. */
    private static boolean matches(String spec, String value) {
        for (String token : spec.split(",")) {
            if (token.trim().equals(value)) {
                return true;
            }
        }
        return false;
    }

    private static void runCell(PrintWriter out, double theta, Arm arm, int seeds,
            double backoffScale, int txnLen, int cores, boolean pairAlphaSeeds, int opBatch,
            Integer lockPool, boolean affinity) {
        SimulatorParameters base = SeedSweepRunner.copyBuilder(
                        WorkloadAxisDefaults.baseBuilder().build())
                .zipfTheta(theta)
                .semanticOverlap(arm.f())
                .cprInitialAttractiveness(arm.alpha())
                .backoffScale(backoffScale)
                .constantTransactionSize(txnLen)
                // THE ARM'S TWO FIELDS. Everything else on this builder is shared, so a difference
                // between two arms of the same f can only come from these - which is what makes the
                // triggered/inlined pair a one-variable comparison.
                .lockingProtocol(arm.protocol())
                .oracleClosurePreclaim(arm.preclaim())
                .oracleClosurePlacement(arm.placement())
                .operationBatchSize(opBatch)
                .lockPoolSize(lockPool)
                .groupUnitAffinity(affinity)
                // BOTH POOLS, and maxConcurrentRunning deliberately left alone - the same two
                // lines WorkloadAxisSweep.build() runs for axis "cores". Setting one pool only
                // would make the IO and CPU stages different widths and turn a capacity sweep
                // into a pipeline-imbalance sweep.
                .numberOfIoProcessors(cores)
                .numberOfCpuProcessors(cores)
                .build();
        WorkloadAxisDefaults.assertLockAliasingOff(base);
        WorkloadAxisDefaults.assertMplIsRecorded(base);
        // Skipped when affinity is the ARM rather than an accident - the value is recorded in
        // every row either way, so a reader can tell the two apart without the filename.
        if (!affinity) {
            assertAffinityOff(base);
        }

        // The campaign's own cell coordinates, so the salt - and therefore the seed stream - is
        // identical to the axis sweep's wherever the coordinates coincide. That is what makes the
        // tps cross-check bit-for-bit rather than merely close.
        WorkloadCellId id = new WorkloadCellId(arm.f(), WorkloadAxisDefaults.BASE_MU,
                WorkloadAxisDefaults.BASE_M, WorkloadAxisDefaults.BASE_TOPOLOGY.name(),
                // ALPHA PINNED IN THE CELL KEY while sweeping alpha, so every alpha draws the
                // SAME five seeds. The key IS the seed salt and alpha is one of its coordinates,
                // so by default each alpha gets its own seeds and the points can only be compared
                // as means - but the question is whether throughput FLATTENS as concentration
                // reaches its floor, and that is far easier to see within a seed than across five
                // noisy means.
                //
                // Only the SEED DERIVATION is pinned; the graph is still built with the arm's
                // own alpha.
                //
                // WHAT THIS DOES *NOT* BUY, measured rather than assumed: the graphs are NOT the
                // same draw with different targets. Max out-degree and even the total edge count
                // vary per seed across alpha (seed 0: out-degree 4,3,4,4,4,5,4 and edges
                // 92,84,90,106,94,95,89), and both are fixed by binomials drawn BEFORE any
                // alpha-dependent choice - so the RNG streams diverge somewhere on this path. A
                // direct CprSchemaBuilder probe at the same seeds DID show invariance, which is
                // what made the stronger claim look safe.
                //
                // Consequence for analysis: these runs share a seed LABEL, not a graph. Do not
                // draw per-seed lines across alpha - they would assert a pairing that is not
                // there. Plot each run against its OWN measured cpr_distinct_targets, which is
                // valid however the draws fall.
                //
                // It also pairs these with the campaign's alpha = 1.0 cell (the f = 0.25 bar),
                // whose salt already carries BASE_ALPHA. NOT with alpha = 0.05 or 100, whose
                // salts carry their own alpha - those two stay means-only unless re-run here.
                pairAlphaSeeds ? WorkloadAxisDefaults.BASE_ALPHA : arm.alpha(),
                // cores, NOT the base constant, for the reason spelled out for txnLen below:
                // the cell key IS the seed salt, so passing the base here would make every core
                // count draw the SAME seeds and carry a cell_id claiming 24 cores.
                theta, WorkloadAxisDefaults.BASE_READ_ONLY_FRACTION, cores,
                // txnLen, NOT the base constant. The cell key IS the seed salt, so passing the
                // base here would make k = 25 and k = 50 draw the SAME seeds as k = 10 - and,
                // worse, carry a cell_id claiming klen10. Threading it through is what keeps
                // these rows joinable to the txnlen axis's own cells, which is what lets the
                // bars and the throughput points describe the same runs.
                txnLen);
        long[] seedArray = SeedSweepRunner.saltedSeeds(seeds, id.salt());

        SeedSweepRunner runner = new SeedSweepRunner();
        for (long seed : seedArray) {
            var outcomes = runner.runRawWithCensoring(
                    String.format(Locale.US, "tb_%s", id.key()), base, new long[] {seed});
            if (outcomes.isEmpty()) {
                continue;
            }
            writeRow(out, id, theta, arm, seed, base, outcomes.get(0));
        }
    }

    private static void writeRow(PrintWriter out, WorkloadCellId id, double theta, Arm arm,
            long seed, SimulatorParameters params, SimulationRunner.SimulationRunOutcome outcome) {
        ExperimentRuntimeSnapshot s = outcome.snapshot();

        // TIME-WEIGHTED STATE COUNTS. Two earlier estimators built on per-lineage histories both
        // failed the Little's-law gate, in opposite directions: averaging over COMPLETED lineages
        // read 9.4x LOW (the stuck tail never completes, so it never enters the average), and
        // including in-flight lineages without clipping over-counts warm-up time. Both were
        // patches on an approach whose edge cases - censoring, warm-up, partial lifetimes - exist
        // only because it tries to reconstruct per-transaction histories at all.
        //
        // Integrating the phase POPULATION has none of them. A transaction is in exactly one
        // phase at every instant, so occupancy[P] is a true partition of population-time: nothing
        // is censored (a still-running transaction is simply in some phase at every step),
        // warm-up is just where integration starts, and in-flight transactions count naturally.
        // Per-transaction time in P is occupancy[P] / completions, and Little's law then holds BY
        // CONSTRUCTION rather than as a property to be checked and patched.
        double[] occ = s.phaseOccupancy();
        long windowTicks = s.phaseOccupancyWindowTicks();
        int completions = s.completionsInWindow();
        // 6, NOT 5. The guard is the schema check for this row: a binary whose LineagePhase still
        // has five constants cannot fill blocked_ticks, and writing a 0 there would be
        // indistinguishable from "nothing blocked" (the zero-vs-unwired distinction).
        if (occ.length < 8 || completions <= 0 || windowTicks <= 0) {
            return;
        }
        double d = completions;
        double occQueue = occ[LineagePhase.PENDING_QUEUE.ordinal()] / d;
        double occBackoff = occ[LineagePhase.BACKOFF.ordinal()] / d;
        double occLockWait = occ[LineagePhase.ADMISSION_WAIT.ordinal()] / d;
        double occRunning = occ[LineagePhase.RUNNING.ordinal()] / d;
        double occCascade = occ[LineagePhase.WAITING_ROOM.ordinal()] / d;
        // BLOCKED, in its three buckets. Split by what the block INTERRUPTED, because they are
        // not the same quantity: blocked-from-running is a parent waiting for a lock it needs to
        // do its own work, while blocked-from-waiting-room is a parent that already finished and
        // was force-blocked by a sibling's denial - that one is arguably cascade delay, not
        // contention. occOther is expected near zero and is emitted so that is a reading.
        double occBlockedRun = occ[LineagePhase.BLOCKED_RUNNING.ordinal()] / d;
        double occBlockedWait = occ[LineagePhase.BLOCKED_WAITING_ROOM.ordinal()] / d;
        double occBlockedOther = occ[LineagePhase.BLOCKED_OTHER.ordinal()] / d;
        double occBlocked = occBlockedRun + occBlockedWait + occBlockedOther;

        // RUNNING splits into the transaction's own service and the queueing around it. Service
        // is exact - a parent is one object on one processor - and taken on the REALISED
        // footprint rather than nominal k, since dedup can put it below k. Verified at
        // MPL = 1, where there is no processor queueing: it read 20,020 ticks, exactly
        // 10 x (1000 + 1000 + 1 + 1).
        //
        // WASTED WORK IS NOT A SEGMENT HERE, and that is a deliberate loss. It is a property of a
        // PAST attempt, not a current state, so a population count cannot see it - a transaction
        // is simply RUNNING whether or not that attempt will later abort. It therefore sits
        // inside running, and BACKOFF carries the abort cost. Backoff dominated wasted work by
        // ~100x at every contention level measured with the per-lineage estimator, so the figure
        // loses very little; what it gains is an estimator with no per-transaction bookkeeping
        // left to get wrong.
        double meanParentRefs = s.completedGroupResourceReferenceSamples() == 0 ? 0.0
                : s.completedGroupParentResourceReferencesTotal()
                        / (double) s.completedGroupResourceReferenceSamples();
        // The CU side, over the SAME completed-group denominator - which is what makes
        // parent + cpr a total per logical transaction rather than two unrelated averages.
        double meanCprRefs = s.completedGroupResourceReferenceSamples() == 0 ? 0.0
                : s.completedGroupCprResourceReferencesTotal()
                        / (double) s.completedGroupResourceReferenceSamples();
        double perResourceTicks = params.ioTimePerResource + params.cpuTimePerResource
                + params.lockIOTimePerResource + params.lockCPUTimePerResource;
        // NO CLAMP. Math.min(service, occRunning) here would disable the check written to catch
        // a wrong service constant: clamped, processor wait can never go negative, so the guard
        // can never fire. A guard whose input is clamped into its passing range is not a guard. If
        // service exceeds running, that IS the signal - report it and let the plot refuse.
        double service = meanParentRefs * perResourceTicks;

        // WASTED WORK, recovered without any per-transaction history.
        //
        // occRunning is the running time of EVERY attempt, including ones that later aborted,
        // while service is one attempt's work - so calling the remainder "processor wait" would
        // book re-executed service as queueing, and at high skew that is most of it.
        //
        // Under C2PL the parent preclaims its whole footprint and finishes its service before any
        // CU fires, and a group abort originates in a CU's hard deny. So an aborting attempt has
        // ALWAYS completed its full service first: each restart wastes exactly one service, no
        // more and no less. That makes the split arithmetic rather than bookkeeping.
        //
        // The premise is checkable and is checked: if a restart wasted LESS than a full service,
        // wasted would be over-charged and processor wait would go negative. It must stay >= 0 in
        // every cell, which is the same guard the clamp above was defeating.
        double restartsPerCompletion = s.groupsCompletedTotal() == 0 ? 0.0
                : s.restartsTotal() / (double) s.groupsCompletedTotal();
        double wasted = restartsPerCompletion * service;
        double processorWait = occRunning - service - wasted;
        double occTotal = occQueue + occBackoff + occLockWait + occRunning + occCascade + occBlocked;

        // GATE 1 - EQUILIBRIUM, and it uses no throughput estimate at all. The parent population
        // is pinned at the admission cap, so total population-time must equal MPL x window to
        // within tick quantisation. This is the stronger check: it says the split is a true
        // partition of population-time. The per-snapshot min/max localise a leak to the step
        // where it happens rather than smearing it through the integral.
        double expectedPopTime = (double) params.maxConcurrentRunning * windowTicks;
        double popRatio = expectedPopTime <= 0 ? 1.0 : (occTotal * d) / expectedPopTime;

        // GATE 2 - LITTLE'S LAW, which follows from gate 1 but is stated in the figure's own
        // units, so a reader can re-derive it from the throughput figure.
        double littleW = s.windowedThroughput() <= 0 ? 0.0
                : params.maxConcurrentRunning / s.windowedThroughput() * 1_000_000.0;
        double littleRatio = littleW <= 0 ? 1.0 : occTotal / littleW;

        // Cores read off params rather than passed in, so the column cannot disagree with what
        // the run was actually configured with. The two pools are set together in runCell, and
        // assertPoolsMatch() below refuses the row if that ever stops being true.
        if (params.numberOfIoProcessors != params.numberOfCpuProcessors) {
            throw new IllegalStateException(String.format(Locale.US,
                    "%s seed %d: IO pool %d != CPU pool %d - 'cores' is not a single number for "
                    + "this run and the column would be a guess", id.key(), seed,
                    params.numberOfIoProcessors, params.numberOfCpuProcessors));
        }
        Map<Property, Integer> inDeg = new HashMap<>();
        int cprEdges = 0;
        int maxOut = 0;
        for (Map.Entry<Property,
                List<Property>> e
                // Model pool rebuilt exactly as SimulationRunner.buildContext does it -
                // new ModelFactory(instancePoolSize).createModels(propertiesPerModel) -
                // because modelPool lives on SimulatorConfig, not on params. Constructing
                // it any other way would measure a graph the run never built - a gate building
                // its subject differently from the system under test.
                : new CprSchemaBuilder(
                        new ModelFactory(params.instancePoolSize)
                                .createModels(params.propertiesPerModel),
                        params.cprTopologyMode, params.semanticOverlap, params.avgCprsPerResource,
                        params.maxCprsPerResource, params.semanticOverlapSeed,
                        params.cprInitialAttractiveness, params.cprAcyclicTotalOrder)
                    .build().asGraph().entrySet()) {
            maxOut = Math.max(maxOut, e.getValue().size());
            for (Property t : e.getValue()) {
                inDeg.merge(t, 1, Integer::sum);
                cprEdges++;
            }
        }
        int maxIn = inDeg.isEmpty() ? 0 : Collections.max(inDeg.values());
        double maxShare = cprEdges == 0 ? 0.0 : (double) maxIn / cprEdges;

        out.printf(Locale.US, "%s,%.3f,%s,%.4f,%.4f,%d,%.4f,%d,%d,%d,%d,%d,%d,"
                        + "%.1f,%.1f,%.1f,%.1f,%.1f,%.1f,%.1f,%.1f,%.1f,%.1f,%.1f,"
                        + "%.4f,%.4f,%d,%d,%.6f,%d,%d,%.6f,%.6f,%s,%s,%b,%b,%d,%.4f,%.4f,"
                        + "%s,%.4f,%d,%d,%d,%.1f,%.1f,%.1f,%.3f%n",
                id.key(), theta, arm.label(), arm.f(), arm.alpha(), seed,
                s.windowedThroughput(), completions, windowTicks, params.maxConcurrentRunning,
                params.numberOfIoProcessors,
                s.phasePopulationMin(), s.phasePopulationMax(),
                occTotal, occQueue, occLockWait, occBackoff, wasted, service, processorWait,
                occCascade, occBlockedRun, occBlockedWait, occBlockedOther,
                meanParentRefs, params.backoffScale,
                inDeg.size(), maxIn, maxShare, maxOut, cprEdges,
                popRatio, littleRatio,
                outcome.terminatedBy().name(),
                // OFF THE PARAMS, NOT OFF THE ARM. Same reasoning as `cores` above: a column that
                // echoed arm.preclaim() would agree with the label by construction and could not
                // catch copyBuilder dropping the field - which is exactly how lockingProtocol was
                // lost once before, making a whole session's "S2PL" data C2PL.
                params.lockingProtocol, params.oracleClosurePreclaim, params.groupUnitAffinity,
                params.operationBatchSize,
                meanCprRefs, meanParentRefs + meanCprRefs,
                params.oracleClosurePreclaim ? params.oracleClosurePlacement.name() : "TRIGGERED",
                restartsPerCompletion,
                params.lockPoolSize != null ? params.lockPoolSize
                        : Math.min(params.numberOfIoProcessors, params.numberOfCpuProcessors),
                s.windowedAbortedParentAttempts(), s.windowedAbortedCuAttempts(),
                s.windowedAbortedParentAttempts() == 0 ? 0.0
                        : s.windowedDiscardedParentService() / s.windowedAbortedParentAttempts(),
                s.windowedAbortedCuAttempts() == 0 ? 0.0
                        : s.windowedDiscardedCuService() / s.windowedAbortedCuAttempts(),
                (s.windowedDiscardedParentService() + s.windowedDiscardedCuService()) / d,
                s.windowedAbortedParentAttempts() == 0 ? 0.0
                        : s.windowedDiscardedParentOpsReached()
                                / (double) s.windowedAbortedParentAttempts());
        out.flush();

        // ONLY AN OVERSHOOT IS A LEAK, and the distinction is the whole value of this check.
        //
        // A transaction that enters a phase without leaving its previous one is counted twice, so
        // a leak pushes the live count ABOVE the cap and keeps climbing - which is exactly what
        // an unguarded bookkeeper markPhase(WAITING_ROOM) does: bookkeepers enter the population
        // and, never reaching finalizeLineageTimeAccounting, never leave.
        //
        // Dipping BELOW is physical rather than a fault: MPL is a cap, and between a completion and the
        // next dispatch the population is genuinely short. Measured [185, 192] at
        // alpha=0.05/theta=0.8 - a 3.6% instantaneous dip whose time-weighted effect is 6e-6,
        // since the equilibrium gate reads 0.999994 on the same run. Asserting exact equality at
        // every snapshot would fail on correct behaviour, which is how a check stops being read.
        if (s.phasePopulationMax() > params.maxConcurrentRunning) {
            System.err.printf(Locale.US,
                    "    POPULATION LEAK %s seed %d: live count reached %d, ABOVE the cap of %d - "
                    + "a transaction entered a phase without leaving its previous one%n",
                    id.key(), seed, s.phasePopulationMax(), params.maxConcurrentRunning);
        }
        // HARD FAILURE, not a warning - and this is the check that covers BOTH leak directions.
        //
        // The overshoot check above catches a double ENTRY (a transaction counted in two phases
        // at once), which drifts the count up. A double EXIT drifts it DOWN, and there it is
        // indistinguishable by eye from ordinary refill lag between a completion and the next
        // dispatch. The equilibrium ratio separates them: refill lag is instantaneous and costs
        // 6e-6 of population-time, while an accumulating downward drift pulls this ratio below 1
        // and keeps pulling. So the ratio, not the instantaneous range, is the load-bearing gate.
        //
        // The row is written before throwing, so the failure leaves evidence rather than only a
        // stack trace.
        // COVERAGE OF THE ABORT HOOK, asserted on the COUNT rather than inferred from the sum.
        //
        // recordDiscardedServiceAtAbort fires from TransactionGroupAbortCoordinator's
        // transitionMembersToAborted. If a future change routes some aborts around that path, the
        // discarded-service columns would quietly under-report and still look plausible - the
        // failure mode this project has hit twice with hooks on paths the population does not take.
        // Every group abort aborts exactly one parent, so the two counts must match exactly.
        // Compared against restartsTotal, NOT totalAbortedGroups: despite its name the latter is a
        // MEMBER-level counter (~4x larger - the snapshot's own javadoc flags the mislabeling).
        // TransactionGroupAbortCoordinator increments restartsTotal exactly once per group abort,
        // immediately after transitionMembersToAborted, so the two are 1:1 by construction.
        //
        // The check has real teeth rather than being a tautology: transitionMembersToAborted skips
        // a parent already in ABORTED, while restartsTotal increments regardless - so a parent
        // reaching the abort path twice shows up here as a shortfall.
        if (s.abortedParentAttemptsTotal() != s.restartsTotal()) {
            throw new IllegalStateException(String.format(Locale.US,
                    "ABORT HOOK COVERAGE FAIL %s seed %d: recorded %d aborting parent attempts but "
                    + "restartsTotal is %d. The discarded-service instrument is not on the path "
                    + "every abort takes, so its columns under-report by the difference.",
                    id.key(), seed, s.abortedParentAttemptsTotal(), s.restartsTotal()));
        }
        if (popRatio < 0.98 || popRatio > 1.02) {
            throw new IllegalStateException(String.format(Locale.US,
                    "EQUILIBRIUM FAIL %s seed %d: population-time %.0f vs MPL x window %.0f "
                    + "(%.4fx). The six phases are not a partition of population-time - a "
                    + "transaction is being counted twice, or leaving a phase twice. Population "
                    + "ranged [%d, %d] against a cap of %d.",
                    id.key(), seed, occTotal * d, expectedPopTime, popRatio,
                    s.phasePopulationMin(), s.phasePopulationMax(), params.maxConcurrentRunning));
        }
        if (littleRatio < 0.98 || littleRatio > 1.02) {
            System.err.printf(Locale.US,
                    "    LITTLE FAIL %s seed %d: occupancy %.0f vs L/lambda %.0f (%.3fx)%n",
                    id.key(), seed, occTotal, littleW, littleRatio);
        }
    }
}
