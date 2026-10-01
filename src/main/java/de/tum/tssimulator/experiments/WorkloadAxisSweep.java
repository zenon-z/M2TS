package de.tum.tssimulator.experiments;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Random;
import java.util.TreeSet;

import de.tum.tssimulator.bookkeeping.CprGraphMetrics;
import de.tum.tssimulator.bootstrap.ExperimentRuntimeSnapshot;
import de.tum.tssimulator.config.SimulatorParameters;
import de.tum.tssimulator.core.Model;
import de.tum.tssimulator.core.ModelFactory;
import de.tum.tssimulator.core.Transaction;
import de.tum.tssimulator.cpr.CprSchema;
import de.tum.tssimulator.cpr.CprSchemaBuilder;
import de.tum.tssimulator.experiments.support.EffectiveConfigHasher;
import de.tum.tssimulator.experiments.support.ExperimentOutputPaths;
import de.tum.tssimulator.experiments.support.SeedSweepRunner;
import de.tum.tssimulator.experiments.support.WorkloadAxisDefaults;
import de.tum.tssimulator.experiments.support.WorkloadCellId;
import de.tum.tssimulator.locking.policy.CautiousWaitingPolicy;

/**
 * W1-W4: four workload-axis figures, one driver, one axis per invocation
 * ({@code -Dts.wl.axis=theta|readonly|cores|txnlen}) - the same symmetry as one plotting function
 * called four times.
 *
 * <p>Each axis is swept against the shared 4-panel CPR template: CPR fraction {@code f}, fan-out
 * {@code mu}, cap {@code m}, and in-degree concentration alpha, plus a no-CPR control ({@code f = 0}) replotted in every
 * panel.
 *
 * <h2>Two things are called k</h2>
 *
 * <p>The spec's <b>k is TRANSACTION LENGTH</b> ({@code constantTransactionSize}); the repo's
 * {@code k} is {@code admissionBatchSize}, which is what the {@code k} column of
 * {@code RawRunRow}/{@code ConfigId} means. The transaction-length axis is written to the sidecar
 * as {@code txn_len} and never into that column.
 *
 * <h2>Why a sidecar rather than extending RawRunRow</h2>
 *
 * <p>{@code RawRunRow} is a fixed schema many scripts read. The sidecar carries one row per cell,
 * joined on {@code config_id}: all eight axis values, the realised quantities, the gate
 * verdict, and {@code total_locks} plus realised lock granularity - which {@code RawRunRow} does
 * not record at all, so aliasing is otherwise unrecoverable from any raw CSV.
 *
 * <h2>Per-cell fault isolation</h2>
 *
 * <p>Engine faults are caught per cell and written as a skipped row rather than allowed to kill the
 * sweep. This is not hypothetical: a rollback-starvation livelock surfaced as
 * {@code "Cannot assign transaction ... in state ABORTED"} and took out an entire multi-hour run at
 * the cell where it fired. {@code SimulationRunner}'s censoring paths do not help - they convert
 * {@code SimulationWatchdogException} into a censored outcome, while an engine invariant throws
 * {@code IllegalStateException}, which propagates. One faulting cell must cost one cell.
 */
public final class WorkloadAxisSweep {

    /** The panel template: 12 CPR configs + 1 control, deduplicated against the base. */
    private record PanelConfig(String panel, String label, double f, double mu, int m,
            double alpha, boolean groupUnitAffinity) {

        PanelConfig(String panel, String label, double f, double mu, int m, double alpha) {
            this(panel, label, f, mu, m, alpha, WorkloadAxisDefaults.BASE_AFFINITY);
        }
    }

    private WorkloadAxisSweep() {
    }

    public static void main(String[] args) throws IOException {
        String axis = System.getProperty("ts.wl.axis", "").trim().toLowerCase(Locale.US);
        if (axis.isEmpty()) {
            throw new IllegalArgumentException(
                    "-Dts.wl.axis=theta|readonly|cores|txnlen is required");
        }
        int seeds = Integer.getInteger("ts.wl.seeds", 5);
        if (seeds < 1) {
            throw new IllegalArgumentException("-Dts.wl.seeds=" + seeds + " - a cell needs at "
                    + "least one seed. Rejected here rather than mid-sweep, where it surfaces as "
                    + "a seed assertion firing on params that were never seeded because there was "
                    + "no seed to apply.");
        }

        // SHARDING, so a many-core host can run the grid as N processes instead of one loop.
        //
        // The split is taken AFTER the shuffle and by position, so shard membership is a
        // deterministic function of (shuffle seed, shard count) and the union of all shards is
        // exactly the unsharded cell list - no cell run twice, none dropped. Sharding on the
        // PRE-shuffle order would hand each shard a contiguous slice of the axis, which is the
        // ordered-sweep contamination the shuffle exists to prevent, re-introduced one level up:
        // any per-host drift would then align with the axis.
        //
        // Separate processes rather than threads remains mandatory - TransactionAggregateMetrics
        // and friends are process-global - and sharding is the supported way to get parallelism
        // without violating that. Each shard writes its own files; concatenate them afterwards
        // (headers are identical, so keep the first and drop the rest).
        int shardCount = Integer.getInteger("ts.wl.shards", 1);
        int shardIndex = Integer.getInteger("ts.wl.shard", 0);
        if (shardCount < 1 || shardIndex < 0 || shardIndex >= shardCount) {
            throw new IllegalArgumentException(String.format(Locale.US,
                    "-Dts.wl.shard=%d is not a valid index into -Dts.wl.shards=%d "
                            + "(expected 0 <= shard < shards)", shardIndex, shardCount));
        }
        // COMMON RANDOM NUMBERS on the graph draw. Default OFF, so the campaign path below is
        // byte-identical to the one that produced the committed rows.
        //
        // WHY. WorkloadCellId.key() carries every axis coordinate, so each x-point gets its own
        // salt, and the driver derives BOTH seeds from it - semanticOverlapSeed included. Every
        // x-point therefore draws an INDEPENDENT CPR graph, and the curve mixes graph-draw
        // variance into the axis effect. Harmless at f <= 0.25, where the closure is subcritical
        // and stable; at f = 0.80 the closure percolates and one draw in forty came in at 41 CPR
        // edges fired per completion against a floor of 59 elsewhere on the line - 2.6x the
        // throughput of any other run, and enough on its own to lift theta=0 above theta=0.3 and
        // make the line look non-monotone in skew. Without that single draw theta=0's other four
        // seeds mean 96.7 tps against theta=0.3's 98.5, and the bump is gone.
        //
        // WHAT THIS CHANGES. The graph seed comes from a salt with the SWEPT COORDINATE PINNED to
        // its base value, while transactionSamplingSeed keeps the fully-salted seed. So seed i
        // uses the same schema at every x-point and a different arrival draw at each, and the
        // line shows the axis effect alone.
        //
        // THE TRADE-OFF, STATED RATHER THAN HIDDEN. Under CRN the whole line leans on the same
        // five graphs. If one of them is the small-closure kind it shifts EVERY point instead of
        // creating a bump - the error becomes a level offset rather than a spurious feature. That
        // is the right trade for showing a trend and the wrong one for estimating a level, so a
        // CRN line must not be read as an absolute-throughput measurement.
        boolean crn = Boolean.getBoolean("ts.wl.crn");
        // Panel filter, so the CRN rerun costs 8 x-points x 5 seeds rather than the whole grid.
        // Format "panel/label", e.g. -Dts.wl.only=A/high. Exact match; a typo fails loudly below
        // rather than silently running everything.
        String only = System.getProperty("ts.wl.only");
        // F x MU GRID, driver-only. panelConfigs() is a fixed list and the four axes are theta /
        // readonly / cores / txnlen, so without these flags an (f, mu) grid needs a code edit.
        // They are named after the PARAMETERS they set - semanticOverlap, avgCprs, maxCprs - so a
        // reader can map a flag to a field without a lookup table.
        //
        // Present => they REPLACE panelConfigs() with the cross-product of the lists given. Absent
        // => panelConfigs() is untouched, which is the first regression check.
        String fSpec = System.getProperty("ts.wl.semanticOverlap");
        String muSpec = System.getProperty("ts.wl.avgCprs");
        String mSpec = System.getProperty("ts.wl.maxCprs");
        // ALPHA IS A GRID COORDINATE TOO. Without it a 2^3 factorial over (f, mu, alpha) is not
        // expressible at all: panelConfigs varies alpha only at the base f and mu (panel D), so
        // the four (f, mu) corners never meet the two alpha levels.
        String aSpec = System.getProperty("ts.wl.cprAlpha");
        boolean gridMode = fSpec != null || muSpec != null || mSpec != null || aSpec != null;
        if (gridMode && only != null) {
            throw new IllegalArgumentException(
                    "-Dts.wl.only selects from panelConfigs() while -Dts.wl.semanticOverlap/"
                    + "avgCprs/maxCprs REPLACE it; pick one");
        }
        // AXIS VALUE FILTER. -Dts.wl.axis is mandatory and sweeps its whole list, so a single-point
        // grid (theta = 0.5 only) needed this. Values are validated as members of the axis: an
        // off-axis value would produce a cell with no point on any published curve to sit beside,
        // the same rule ts.tb.txnlen and ts.tb.cores already enforce.
        String axisOnly = System.getProperty("ts.wl.axisOnly");
        // ONE SEED PER JOB. -Dts.wl.seedIndex=i runs only the i-th of a cell's salted seeds, so a
        // grid can be submitted as (cells x seeds) independent jobs instead of (cells) jobs that
        // each loop over every seed.
        //
        // THE FULL SEED ARRAY IS STILL DRAWN AND THEN INDEXED, never re-derived from i. saltedSeeds
        // is a pure function of (count, salt), so element i of a 5-element draw is NOT element 0 of
        // a 1-element draw - asking for one seed directly would silently give a different workload
        // and the rows would look fine. Indexing the full array is what makes a sharded-by-seed run
        // reproduce a serial run exactly, which the third regression check asserts.
        Integer seedIndex = Integer.getInteger("ts.wl.seedIndex");
        if (seedIndex != null && (seedIndex < 0 || seedIndex >= seeds)) {
            throw new IllegalArgumentException(String.format(Locale.US,
                    "-Dts.wl.seedIndex=%d is out of range for -Dts.wl.seeds=%d (valid 0..%d). "
                    + "An out-of-range index would otherwise produce an empty cell, which reads as "
                    + "'this cell was gated out' rather than 'the job list is wrong'.",
                    seedIndex, seeds, seeds - 1));
        }
        // CRN rows must never land in the campaign's files. They are a different experiment with
        // a different seeding rule, and a reader who concatenated them would be averaging two
        // designs. The tag also makes the mode visible in the filename, which is the first thing
        // anyone looks at.
        // THE GRID AND THE AXIS FILTER GO IN THE NAME. Grid rows are a different experiment with
        // different cells; a reader who concatenated them with the campaign's would be averaging
        // two designs, and an axis-filtered file is not the campaign's sweep either.
        String modeTag = (crn ? "_crn" : "")
                + (only == null ? "" : "_" + only.replace('/', '-'))
                + (gridMode ? "_grid"
                        + (fSpec == null ? "" : "_f" + fSpec.replaceAll("[^0-9.]", "-"))
                        + (muSpec == null ? "" : "_mu" + muSpec.replaceAll("[^0-9.]", "-"))
                        + (mSpec == null ? "" : "_m" + mSpec.replaceAll("[^0-9.]", "-"))
                        + (aSpec == null ? "" : "_a" + aSpec.replaceAll("[^0-9.]", "-"))
                        : "")
                + (axisOnly == null ? "" : "_x" + axisOnly.replaceAll("[^0-9.]", "-"))
                // THE SEED INDEX GOES IN THE NAME. 35 jobs writing to one directory must resolve to
                // 35 distinct paths; two jobs on one path interleave into a corrupted csv rather
                // than failing, which is how 35 rows of an earlier sweep were lost.
                + (seedIndex == null ? "" : "_si" + seedIndex);
        String suffix = (shardCount == 1 ? "" : "_shard" + shardIndex) + modeTag;

        List<PanelConfig> configs = gridMode
                ? gridConfigs(fSpec, muSpec, mSpec, aSpec)
                : panelConfigs();
        if (only != null) {
            List<PanelConfig> kept = new ArrayList<>();
            for (PanelConfig cfg : configs) {
                if (only.equals(cfg.panel() + "/" + cfg.label())) {
                    kept.add(cfg);
                }
            }
            if (kept.isEmpty()) {
                List<String> available = new ArrayList<>();
                for (PanelConfig cfg : configs) {
                    available.add(cfg.panel() + "/" + cfg.label());
                }
                throw new IllegalArgumentException("-Dts.wl.only=" + only
                        + " matched no panel config; available: " + available);
            }
            configs = kept;
        }
        double[] xs = axisValues(axis);
        if (axisOnly != null) {
            List<Double> keep = new ArrayList<>();
            for (String tok : axisOnly.split(",")) {
                double v = Double.parseDouble(tok.trim());
                boolean onAxis = false;
                for (double x : xs) {
                    onAxis |= Math.abs(x - v) < 1e-9;
                }
                if (!onAxis) {
                    throw new IllegalArgumentException(String.format(Locale.US,
                            "-Dts.wl.axisOnly=%s is not a point on axis '%s' %s", tok.trim(), axis,
                            Arrays.toString(xs)));
                }
                keep.add(v);
            }
            double[] filtered = new double[keep.size()];
            for (int i = 0; i < keep.size(); i++) {
                filtered[i] = keep.get(i);
            }
            xs = filtered;
        }

        String rawPath = ExperimentOutputPaths.resultsFile("wl_" + axis + suffix + "_raw.csv");
        String cellPath = ExperimentOutputPaths.resultsFile("wl_" + axis + suffix + "_cells.csv");
        // The REASON behind unequal seeds_used, once per file rather than 40 times per row - and
        // in a companion file rather than a leading "#" line, because the plotting scripts read
        // these with stdlib csv.DictReader, which would take a comment line as the header.
        // ONE writer, not one per shard. The content is identical across shards, so this file
        // deliberately carries no shard suffix - but that makes every shard a concurrent writer to
        // one path, and N processes truncating and rewriting the same file is a race whose most
        // likely outcome is a half-written file that still looks plausible. Shard 0 writes it.
        if (shardIndex == 0) {
            // modeTag, not a bare axis name. The shard suffix is deliberately omitted here (one
            // writer for identical content), and a CRN or panel-filtered run would otherwise
            // TRUNCATE the campaign's own metadata file while writing rows to a differently-named
            // CSV - the data safe, its provenance quietly replaced by a different experiment's.
            writeCellsMetadata(ExperimentOutputPaths.resultsFile(
                    "wl_" + axis + modeTag + "_cells.meta.txt"));
        }
        String skipPath = ExperimentOutputPaths.resultsFile("wl_" + axis + suffix + "_skipped.csv");

        // DEDUPLICATED AT ENUMERATION, not in analysis.
        //
        // Panel A/mid, B/mid, C/high and Panel D's alpha=1.0 are ONE SimulatorParameters - they
        // are the base config seen from four panels. Enumerating all thirteen records runs the
        // base cell FOUR TIMES at every x-point: 23 x-points x 13 = 299 cells x 5 seeds = 1495
        // runs against a pre-registered 1000. The repeats are bit-identical, so nothing is
        // mismeasured and nothing shows up in a figure - the cost is compute and a
        // pre-registration mismatch, which is why it goes unnoticed.
        //
        // Each unique config therefore runs ONCE and the result is fanned out to every panel/label it
        // appears under in the sidecar, so the plotting code still finds the base cell under
        // A/mid, B/mid, C/high and D/alpha=1.0 and needs no join of its own.
        //
        // Grouping is by the tuple PanelConfig varies; the effective CONFIG HASH is then asserted
        // equal across each group (see assertAppearancesShareOneConfig). The tuple is the
        // mechanism and the hash is an independent witness to it - if a future field makes two
        // tuple-equal configs differ, the run aborts instead of silently collapsing two cells into
        // one, which is the failure mode that would actually corrupt data rather than waste time.
        //
        // Cell list built then SHUFFLED: host drift over a long run would otherwise align
        // with the sweep order and read as an effect along the axis.
        List<Object[]> cells = new ArrayList<>();
        for (double x : xs) {
            Map<String, List<PanelConfig>> byIdentity = new LinkedHashMap<>();
            for (PanelConfig cfg : configs) {
                byIdentity.computeIfAbsent(identityKey(cfg), unused -> new ArrayList<>()).add(cfg);
            }
            for (List<PanelConfig> appearances : byIdentity.values()) {
                cells.add(new Object[] {x, appearances});
            }
        }
        long shuffleSeed = Long.getLong("ts.wl.shuffle", 20260918L);
        if (shuffleSeed != 0L) {
            Collections.shuffle(cells, new Random(shuffleSeed));
        }

        // CROSS-PROCESS GUARD on the shuffle. Splitting after the shuffle partitions the grid
        // correctly only if every shard computes the IDENTICAL shuffle - which holds because the
        // seed is a fixed constant, but "holds because of a default" is exactly the assumption
        // this plan keeps finding broken. A per-process shuffle seed (someone passing
        // -Dts.wl.shuffle=$RANDOM, or the seed changing between two launches of the same wave)
        // would give every shard a different ordering: cells would be run twice, others never,
        // and the merged CSV would look complete because the ROW COUNT would still be plausible.
        //
        // So each shard prints a fingerprint of its full ordered cell list. The merge step
        // compares them; disagreement means the shards did not partition one grid and the merge
        // is invalid. This is checkable across processes, which a comment is not.
        provenanceLines().forEach(System.out::println);
        long fingerprint = cellListFingerprint(axis, cells, shuffleSeed);
        System.out.printf(Locale.US,
                "cell-list fingerprint %016x (%d cells, shuffle seed %d) - every shard of one "
                        + "wave MUST print this same value%n",
                fingerprint, cells.size(), shuffleSeed);

        int failures = 0;
        int mine = 0;
        try (PrintWriter raw = new PrintWriter(new FileWriter(rawPath));
                PrintWriter cellOut = new PrintWriter(new FileWriter(cellPath));
                PrintWriter skipped = new PrintWriter(new FileWriter(skipPath))) {

            raw.println("config_id,panel,label,axis,x,seed,graph_seed,throughput_tps,completions,"
                    + "measurement_window_s,frac_blocked,frac_waiting_room,aborts_per_completion,"
                    + "hard_deny_rate,unit_utilisation,realized_top1pct_access_share,"
                    + "realized_top10pct_access_share,mean_distinct_instances_touched,"
                    + "writes_per_txn_mean,read_only_txn_frac_realized,cpr_edges_fired_per_completion,"
                    + "admitted_parents_for_access,terminated_by,"
                    // APPENDED, so an archived 1-24 row diffs as two added columns rather than a
                    // reordering. Time-weighted over the post-warm-up window, unlike the
                    // cpr_edges_fired_per_completion beside it which is a per-completion rate:
                    // together they separate "bigger cascades" from "more cascades at once".
                    + "mean_cus_in_flight,peak_cus_in_flight,cus_live_negative_samples");
            cellOut.println("config_id,panel,label,axis,x,f,mu,m,topology,cpr_alpha,acyclic_order,group_unit_affinity,zipf_theta,"
                    + "read_only_fraction,cores,txn_len,mpl,max_concurrent_running,d,total_locks,"
                    + "lock_granularity,realised_f,realised_mu,pigeonhole_ratio,"
                    + "cu_service_to_quantum,cus_per_group,service_demand_ratio,"
                    + "reference_capacity_tps,seeds_requested,seeds_used,gate_verdict");
            skipped.println("config_id,axis,x,reason");

            int n = 0;
            for (int position = 0; position < cells.size(); position++) {
                if (position % shardCount != shardIndex) {
                    continue;
                }
                Object[] cell = cells.get(position);
                double x = (Double) cell[0];
                @SuppressWarnings("unchecked")
                List<PanelConfig> appearances = (List<PanelConfig>) cell[1];
                n++;
                mine = n;
                System.out.printf(Locale.US, "[%d/%d cell %d/%d] axis=%s x=%s %s%n",
                        n, (cells.size() - shardIndex + shardCount - 1) / shardCount,
                        position + 1, cells.size(), axis, fmt(x), describe(appearances));
                if (!runCell(axis, x, appearances, seeds, raw, cellOut, skipped, crn,
                        seedIndex)) {
                    failures++;
                }
            }
        }

        System.out.printf(Locale.US, "%nWrote %s%n       %s%n       %s%n", rawPath, cellPath, skipPath);
        if (shardCount > 1) {
            System.out.printf(Locale.US,
                    "shard %d/%d ran %d of %d cells. Concatenate all %d shards before analysis - "
                            + "a single shard is a STRIDED SUBSET of the grid, not a sample of it, "
                            + "and any figure drawn from one alone is missing cells silently.%n",
                    shardIndex, shardCount, mine, cells.size(), shardCount);
        }
        if (failures > 0) {
            System.out.printf(Locale.US,
                    "%d cell(s) were skipped - see %s. They are RECORDED, not silently dropped; a "
                            + "figure drawn from this data must account for the gaps.%n",
                    failures, skipPath);
        }
    }

    /**
     * Panel A: f. Panel B: mu. Panel C: m. Panel D: alpha. Plus the no-CPR control.
     *
     * <p>Deduplicated against the base: Panel A's mid (f=0.25), Panel B's mid (mu=2), Panel C's
     * high (m=5) and <b>Panel D's MID (alpha=1.0)</b> are all the SAME configuration, so 13
     * nominal configs collapse to 10 distinct ones.
     *
     * <p>The POSITION is what matters, not the label: {@code BASE_ALPHA = 1.0} is the MIDDLE of
     * {@code PANEL_D_ALPHA}, so the dedup lands on the mid. That is why moving Panel D's LOW line
     * from 0.1 to 0.05 is a clean swap that leaves the base cell, the dedup and the run count
     * untouched. Had the base been the low value, the same edit
     * would have re-pointed the base cell and invalidated the theta curve behind it. The panel/label pair is kept per appearance so the plotting
     * code can place one measured config into several panels without re-running it.
     */
    /**
     * The (f, mu, m) cross-product named by {@code -Dts.wl.semanticOverlap} / {@code avgCprs} /
     * {@code maxCprs}. A list left unset contributes its campaign default alone, so
     * {@code -Dts.wl.semanticOverlap=0.2,0.4 -Dts.wl.avgCprs=2} is two cells rather than two times
     * the whole mu axis.
     *
     * <p>Labelled with the VALUES, not an index: the label is what lands in the {@code label}
     * column and in the progress line, and "G/f0.40_mu2.0_m5" is readable in both places where
     * "G/cell3" is not. Panel "G" keeps these out of the A-E panel namespace the figures plot.
     *
     * <p>{@code WorkloadCellId} is built from the parameter VALUES and not from this label, so a
     * grid cell whose values coincide with a campaign cell draws the same salted seeds and must
     * reproduce it exactly - which is the second regression check.
     */
    private static List<PanelConfig> gridConfigs(String fSpec, String muSpec, String mSpec,
            String aSpec) {
        double[] fs = parseDoubles(fSpec, WorkloadAxisDefaults.BASE_SEMANTIC_OVERLAP);
        double[] mus = parseDoubles(muSpec, WorkloadAxisDefaults.BASE_MU);
        double[] ms = parseDoubles(mSpec, WorkloadAxisDefaults.BASE_M);
        double[] as = parseDoubles(aSpec, WorkloadAxisDefaults.BASE_ALPHA);
        List<PanelConfig> out = new ArrayList<>();
        for (double f : fs) {
            for (double mu : mus) {
                for (double m : ms) {
                    for (double a : as) {
                        if (!(a > 0.0)) {
                            throw new IllegalArgumentException(
                                    "cprAlpha must be > 0 (got " + a + "): every in-degree starts "
                                    + "at 0, so alpha = 0 leaves every candidate at weight 0 and "
                                    + "nothing is drawable");
                        }
                        // ALPHA IN THE LABEL, like the other three coordinates. Two cells differing
                        // only in alpha would otherwise share a label and be indistinguishable in
                        // the progress line, the label column and any -Dts.wl.only filter.
                        out.add(new PanelConfig("G",
                                String.format(Locale.US, "f%.2f_mu%.1f_m%d_a%g", f, mu, (int) m, a),
                                f, mu, (int) m, a));
                    }
                }
            }
        }
        return out;
    }

    private static double[] parseDoubles(String spec, double fallback) {
        if (spec == null) {
            return new double[] {fallback};
        }
        String[] toks = spec.split(",");
        double[] out = new double[toks.length];
        for (int i = 0; i < toks.length; i++) {
            out[i] = Double.parseDouble(toks[i].trim());
            if (!(out[i] >= 0.0)) {
                throw new IllegalArgumentException("negative or NaN value in '" + spec + "'");
            }
        }
        return out;
    }

    private static List<PanelConfig> panelConfigs() {
        double f = WorkloadAxisDefaults.BASE_SEMANTIC_OVERLAP;
        double mu = WorkloadAxisDefaults.BASE_MU;
        int m = WorkloadAxisDefaults.BASE_M;
        double a = WorkloadAxisDefaults.BASE_ALPHA;
        double[] alphas = WorkloadAxisDefaults.PANEL_D_ALPHA;

        List<PanelConfig> out = new ArrayList<>();
        // control - at f=0 the other three axes are meaningless, so it is ONE config per x-point.
        out.add(new PanelConfig("control", "no CPR", 0.0, mu, m, a));
        // A: CPR fraction
        out.add(new PanelConfig("A", "low", 0.05, mu, m, a));
        out.add(new PanelConfig("A", "mid", 0.25, mu, m, a));   // == base
        out.add(new PanelConfig("A", "high", 0.80, mu, m, a));
        // B: mu (m stays 5: mu=4 needs m>=4, and p=(mu-1)/(m-1) needs m>1)
        out.add(new PanelConfig("B", "low", f, 1.0, m, a));
        out.add(new PanelConfig("B", "mid", f, 2.0, m, a));     // == base
        out.add(new PanelConfig("B", "high", f, 4.0, m, a));
        // C: m at fixed mu=2, so p=(mu-1)/(m-1)=1/(m-1): same MEAN out-degree, different variance
        out.add(new PanelConfig("C", "low", f, mu, 2, a));
        out.add(new PanelConfig("C", "mid", f, mu, 3, a));
        out.add(new PanelConfig("C", "high", f, mu, 5, a));     // == base
        // D: ALPHA - in-degree concentration - with mu, m and the model graph HELD FIXED.
        //
        // This replaces the three named topologies. Those differed in whether closures terminate
        // structurally (LINE and HOTSPOT acyclic at the model level, FAN_OUT not), so the panel
        // varied topology AND cyclicity and could not attribute its own result - a residual that
        // had to be captioned. One UNIFORM model graph plus a property-level total order makes all
        // three lines share one structure, so Panel D is controlled exactly as A, B and C are.
        //
        // alpha is also continuous, which answers "why these three" - they are three points on an
        // axis rather than three names.
        out.add(new PanelConfig("D", "alpha=" + alphas[0], f, mu, m, alphas[0]));
        out.add(new PanelConfig("D", "alpha=" + alphas[1], f, mu, m, alphas[1])); // == base
        out.add(new PanelConfig("D", "alpha=" + alphas[2], f, mu, m, alphas[2]));

        // Experiment 3 ONLY: the base configuration with group-unit affinity ON (the base is OFF).
        //
        // groupUnitAffinity pins a group's members to one IO unit and one CPU unit, so its cost
        // GROWS with the core count - a no-op at 1 core (nothing to contend for) and a real
        // constraint at 24. Measured at MPL=192 it does not merely bend the capacity curve, it
        // INVERTS it: pinned throughput goes 885 -> 817 tps from 16 to 24 cores while unpinned
        // goes 946 -> 1008. Pinning it in the base config would therefore report a scaling
        // inversion that is a scheduling artifact, and the natural reading of that figure is the
        // paper's own headline - that cross-model consistency costs scaling. So it is measured.
        //
        // Only on the cores axis: that is the only section where the contrast changes SHAPE.
        // Elsewhere it is a constant factor and would buy nothing for the extra runs.
        if (axisNeedsAffinityArm()) {
            out.add(new PanelConfig("E", "pinned", f, mu, m, a, true));
        }
        return out;
    }

    /** The affinity contrast only bends the curve on the cores axis - see panelConfigs(). */
    private static boolean axisNeedsAffinityArm() {
        return Boolean.getBoolean("ts.wl.affinityArm")
                && "cores".equals(System.getProperty("ts.wl.axis", "").trim().toLowerCase(Locale.US));
    }

    /** Package-visible so WorkloadAlphaScan can enumerate the campaign's ACTUAL cells. */
    static double[] axisValues(String axis) {
        return switch (axis) {
            // THE SPACING IS THE RESULT OF A MEASUREMENT, not a preference. An even
            // {0, 0.3, 0.5, 0.7, 0.8, 0.9} puts a 5.5x drop between two ADJACENT samples -
            // capacity share 0.579 at theta=0.5 against 0.106 at 0.7 - with nothing in between,
            // which is zero resolution exactly where the contention result lives.
            //
            // theta=0.9 is DROPPED, not merely unsampled: it reads 0.090 against 0.078 at 0.8, the
            // one non-monotone point on the curve, while the multiplier table is monotone across
            // the whole remaining set - so the point is not needed to establish the shape and its
            // only contribution is a question about seed noise.
            case "theta" -> new double[] {0.0, 0.3, 0.5, 0.55, 0.6, 0.65, 0.7, 0.8};
            case "readonly" -> new double[] {0.0, 0.25, 0.5, 0.75, 0.9};
            // EXTENDED past 24, keeping the first six values and their order, so every archived
            // 1-24 row stays valid and comparable - the regression check re-runs one of them to
            // prove it rather than asserting it.
            //
            // THE CONTROL'S CEILING IS ~9,600 tps AND IT IS REACHED AT 96 CORES, so the control
            // MUST go flat from there and a flat control at 96-128 is not a finding. Two bounds
            // coincide: capacity is cores/(k x io) = cores/0.01s, which is 9,600 at 96 cores; and
            // Little's law caps throughput at MPL/service = 192/0.02002s = 9,590 tps whatever the
            // core count. Past 96 cores the cell is MPL-bound, not capacity-bound. Any figure
            // drawn from these points needs that line marked.
            case "cores" -> new double[] {1, 2, 4, 8, 16, 24, 32, 48, 64, 96, 128};
            case "txnlen" -> new double[] {5, 10, 25, 50};
            default -> throw new IllegalArgumentException("unknown axis: " + axis);
        };
    }

    private static SimulatorParameters build(String axis, double x, PanelConfig cfg) {
        SimulatorParameters.Builder b = WorkloadAxisDefaults.baseBuilder()
                .semanticOverlap(cfg.f())
                .avgCprsPerResource(cfg.mu())
                .maxCprsPerResource(cfg.m())
                .cprInitialAttractiveness(cfg.alpha())
                .groupUnitAffinity(cfg.groupUnitAffinity());
        switch (axis) {
            case "theta" -> b.zipfTheta(x);
            case "readonly" -> b.readOnlyTransactionFraction(x);
            case "cores" -> {
                // MPL is held at saturation and does NOT track cores - that coupling would sweep
                // offered concurrency and service capacity together and make the curve
                // uninterpretable. maxConcurrentRunning stays equal to the population.
                int cores = (int) x;
                b.numberOfIoProcessors(cores).numberOfCpuProcessors(cores);
            }
            case "txnlen" -> b.constantTransactionSize((int) x);
            default -> throw new IllegalArgumentException("unknown axis: " + axis);
        }
        return b.build();
    }

    /**
     * The identity a {@link PanelConfig} runs under - everything it varies, and nothing it does
     * not. {@code panel} and {@code label} are deliberately absent: they are where a result is
     * PLOTTED, not what was run, and including them is what made the base cell four cells.
     *
     * <p>This is also why the four base-equivalent entries already shared a {@code config_id} -
     * {@link WorkloadCellId} omits the panel label too - so the dedup changes which runs happen
     * and changes no key, no salt and no output value.
     */
    private static String identityKey(PanelConfig cfg) {
        return String.format(Locale.US, "f%.6f_mu%.6f_m%d_a%.6f_pin%s",
                cfg.f(), cfg.mu(), cfg.m(), cfg.alpha(), cfg.groupUnitAffinity());
    }

    /**
     * A stable fingerprint of the ORDERED cell list, so shards of one wave can be checked to have
     * partitioned the same grid in the same order. Order-sensitive on purpose: two shards holding
     * the same cells in different orders would still split by position into different subsets.
     */
    private static long cellListFingerprint(String axis, List<Object[]> cells, long shuffleSeed) {
        long h = 1125899906842597L;
        h = h * 31 + axis.hashCode();
        h = h * 31 + shuffleSeed;
        for (Object[] cell : cells) {
            h = h * 31 + Double.hashCode((Double) cell[0]);
            @SuppressWarnings("unchecked")
            List<PanelConfig> appearances = (List<PanelConfig>) cell[1];
            for (PanelConfig cfg : appearances) {
                h = h * 31 + identityKey(cfg).hashCode();
                h = h * 31 + cfg.panel().hashCode();
                h = h * 31 + cfg.label().hashCode();
            }
        }
        return h;
    }

    /** "A/mid + B/mid + C/high + D/alpha=1.0" for a deduplicated group; a single pair otherwise. */
    private static String describe(List<PanelConfig> appearances) {
        List<String> parts = new ArrayList<>();
        for (PanelConfig cfg : appearances) {
            parts.add(cfg.panel() + "/" + cfg.label());
        }
        return String.join(" + ", parts);
    }

    /**
     * The grouping is by {@link #identityKey}; this checks it against the EFFECTIVE config hash,
     * which reflects over every public field of {@link SimulatorParameters}.
     *
     * <p><b>Why an independent witness rather than trust.</b> Collapsing two cells that are not
     * actually identical would publish one cell's numbers under another cell's label - a data
     * corruption, unlike the duplication it replaces, which only wasted time. So the cheap tuple
     * does the grouping and the exhaustive hash refuses the run if the tuple has fallen out of
     * step with it. A new {@code PanelConfig} field that is not in {@code identityKey} fails here
     * instead of silently merging two panels.
     */
    private static void assertAppearancesShareOneConfig(String axis, double x,
            List<PanelConfig> appearances, SimulatorParameters representative) {
        String expected = EffectiveConfigHasher.hash(representative);
        for (PanelConfig other : appearances) {
            String actual = EffectiveConfigHasher.hash(build(axis, x, other));
            if (!actual.equals(expected)) {
                throw new IllegalStateException(String.format(Locale.US,
                        "Deduplication would have merged two DIFFERENT configurations at axis=%s "
                                + "x=%s: %s/%s hashes %s but %s/%s hashes %s. They share an "
                                + "identityKey, so a field PanelConfig varies is missing from it. "
                                + "Fix identityKey; do not relax this check - merging them would "
                                + "publish one cell's numbers under the other's panel label.",
                        axis, fmt(x), appearances.get(0).panel(), appearances.get(0).label(),
                        expected, other.panel(), other.label(), actual));
            }
        }
    }

    private static boolean runCell(String axis, double x, List<PanelConfig> appearances, int seeds,
            PrintWriter raw, PrintWriter cellOut, PrintWriter skipped, boolean crn,
            Integer seedIndex) {
        // The run is executed ONCE under the first appearance; every appearance gets a sidecar row.
        PanelConfig cfg = appearances.get(0);
        SimulatorParameters params;
        WorkloadCellId id;
        try {
            params = build(axis, x, cfg);
            assertAppearancesShareOneConfig(axis, x, appearances, params);
            id = new WorkloadCellId(cfg.f(), cfg.mu(), cfg.m(),
                    params.cprTopologyMode.name() + (cfg.groupUnitAffinity() ? "-pinned" : ""),
                    cfg.alpha(),
                    params.zipfTheta, params.readOnlyTransactionFraction,
                    params.numberOfCpuProcessors, params.constantTransactionSize);
            WorkloadAxisDefaults.assertLockAliasingOff(params);
            WorkloadAxisDefaults.assertMplIsRecorded(params);
        } catch (RuntimeException e) {
            skipped.printf(Locale.US, "%s,%s,%s,\"CONFIG: %s\"%n",
                    "unbuilt", axis, fmt(x), msg(e));
            skipped.flush();
            System.out.println("    SKIPPED (config): " + msg(e));
            return false;
        }

        // Structural gate, before any run: f exactly representable and no out-degree truncation.
        // Deterministic, so it costs nothing and must never be discovered mid-sweep.
        String gate = structuralGate(params);
        if (gate != null) {
            skipped.printf(Locale.US, "%s,%s,%s,\"GATE: %s\"%n", id.key(), axis, fmt(x), gate);
            skipped.flush();
            System.out.println("    SKIPPED (gate): " + gate);
            return false;
        }

        long[] seedArray = SeedSweepRunner.saltedSeeds(seeds, id.salt());
        // Under CRN the graph seed comes from a salt in which the swept coordinate is PINNED to
        // its base value, so it is constant along the axis while every other coordinate still
        // separates cells. Built by re-forming the cell key rather than by hashing a shorter
        // string, so the two salts cannot drift apart if a coordinate is ever added to the key.
        long[] graphSeeds = crn
                ? SeedSweepRunner.saltedSeeds(seeds, crnGraphSalt(id, axis))
                : seedArray;
        int ok = 0;
        for (int si = 0; si < seedArray.length; si++) {
            // Selected AFTER the full array is drawn - see ts.wl.seedIndex's note in main().
            if (seedIndex != null && si != seedIndex) {
                continue;
            }
            long seed = seedArray[si];
            SimulatorParameters seeded = SeedSweepRunner.copyBuilder(params)
                    .transactionSamplingSeed(seed)
                    .semanticOverlapSeed(graphSeeds[si])
                    .build();
            // Process-global statics with their own reset; Transaction.resetMetrics() does NOT
            // clear them, so without this every run after the first reports a running total.
            CautiousWaitingPolicy.resetDiagnosticCounters();
            try {
                SimulationRunner.SimulationRunOutcome outcome = new SimulationRunner()
                        .runDetailedAllowPartial("wl_" + axis + "_" + id.key() + "_s" + seed, seeded);
                writeRaw(raw, id, cfg, axis, x, seed, seeded, outcome, graphSeeds[si]);
                ok++;
            } catch (RuntimeException e) {
                // ENGINE fault, not a watchdog - runDetailedAllowPartial does not catch these.
                // One faulting cell costs one seed, recorded with its message so the pattern is
                // diagnosable afterwards rather than being a hole in the data.
                skipped.printf(Locale.US, "%s,%s,%s,\"ENGINE seed=%d: %s\"%n",
                        id.key(), axis, fmt(x), seed, msg(e));
                skipped.flush();
                System.out.println("    ENGINE FAULT seed=" + seed + ": " + msg(e));
            } finally {
                Transaction.resetMetrics();
            }
        }

        // One sidecar row PER APPEARANCE, all sharing this cell's config_id. That is what lets the
        // plotting code select on (panel, label) and still find the single measured base cell in
        // all four panels it belongs to, without a join or a special case of its own.
        //
        // PASSED THE SEEDED PARAMS, not the bare ones. SimulatorParameters.semanticOverlapSeed is
        // a Long whose null default means "non-deterministic": CprSchemaBuilder falls back to
        // new Random(System.nanoTime()). baseBuilder() never sets it - the seed arrives per run
        // from saltedSeeds - so writeCell(params, ...) was building its schema from the wall
        // clock. Every schema-derived column in the sidecar (realised_mu, cus_per_group,
        // service_demand_ratio, pigeonhole_ratio) was therefore (a) different on every invocation
        // and (b) describing a graph NO run used, while the 4-sigma realised-mu gate and the
        // pigeonhole gate were validating that graph rather than the engine's.
        //
        // Sharding exposes it: the same cell reads realised_mu 2.6 in one process layout and 2.3
        // in another. It is a gate not constructing its subject the way the system under test
        // does, and it is invisible to any check that runs the driver once.
        //
        // seedArray[0] is used, so the columns describe a REAL and reproducible member of this
        // cell's seed set. They are one draw, not the mean over seeds: graph-draw spread across
        // seeds is itself a measured quantity, and collapsing it into a per-cell mean here would
        // hide that variance structure.
        writeCell(cellOut, id, appearances, axis, x, schemaReferenceParams(params, seedArray),
                seedArray.length, ok);
        // EXPECTED SEED COUNT, not the requested one. Under -Dts.wl.seedIndex a job deliberately
        // runs ONE of the cell's seeds, so comparing against seedArray.length would report every
        // such job as a skipped cell - 250 spurious warnings across a grid, and worse, a real gate
        // skip would be indistinguishable from the noise. The skipped.csv file stays the record of
        // genuine skips; this only stops the console line crying wolf.
        int expectedSeeds = seedIndex != null ? 1 : seedArray.length;
        return ok == expectedSeeds;
    }

    /**
     * The params the sidecar's schema-derived columns are computed from: this cell's first salted
     * seed, so the graph they describe is one the engine actually built.
     *
     * <p>Returns the input unchanged when the cell ran no seeds, which keeps a NO_SEEDS row
     * writable rather than throwing while reporting a failure.
     */
    private static SimulatorParameters schemaReferenceParams(SimulatorParameters params,
            long[] seedArray) {
        if (seedArray.length == 0) {
            return params;
        }
        return SeedSweepRunner.copyBuilder(params)
                .transactionSamplingSeed(seedArray[0])
                .semanticOverlapSeed(seedArray[0])
                .build();
    }

    /** {@code f} exactly representable, and {@code m} not truncated by the candidate-target count. */
    private static String structuralGate(SimulatorParameters params) {
        int p = params.totalModels * params.propertiesPerModel.get(0);
        int activeCount = (int) Math.round(p * params.semanticOverlap);
        double realised = p == 0 ? 0.0 : activeCount / (double) p;
        if (Math.abs(realised - params.semanticOverlap) > 1e-12) {
            return String.format(Locale.US, "f=%.4f not representable at %d properties (realised %.4f)",
                    params.semanticOverlap, p, realised);
        }
        if (params.maxCprsPerResource > params.propertiesPerModel.get(0)) {
            return String.format(Locale.US, "m=%d exceeds propertiesPerModel=%d, so the binomial is "
                    + "truncated and realised mu < nominal",
                    params.maxCprsPerResource, params.propertiesPerModel.get(0));
        }
        return null;
    }

    /**
     * Salt for the graph draw under common random numbers: the cell key with the SWEPT coordinate
     * replaced by its base value, so it is invariant along the axis and still separates cells that
     * differ in anything else.
     *
     * <p>Re-formed through {@link WorkloadCellId} rather than hashed from a shorter hand-written
     * string. If a coordinate is ever added to the key, both salts pick it up together; a separate
     * string would silently stop distinguishing cells the workload salt still distinguishes, and
     * two cells sharing a graph seed by accident is exactly the aliasing this campaign's cell-id
     * class exists to prevent.
     */
    private static long crnGraphSalt(WorkloadCellId id, String axis) {
        double theta = "theta".equals(axis) ? WorkloadAxisDefaults.BASE_THETA : id.zipfTheta();
        double ro = "readonly".equals(axis)
                ? WorkloadAxisDefaults.BASE_READ_ONLY_FRACTION : id.readOnlyFraction();
        int cores = "cores".equals(axis) ? WorkloadAxisDefaults.CORES : id.cores();
        int txnLen = "txnlen".equals(axis)
                ? WorkloadAxisDefaults.TRANSACTION_LENGTH : id.txnLen();
        return new WorkloadCellId(id.semanticOverlap(), id.mu(), id.m(), id.topology(),
                id.cprInitialAttractiveness(), theta, ro, cores, txnLen).salt();
    }

    private static void writeRaw(PrintWriter raw, WorkloadCellId id, PanelConfig cfg, String axis,
            double x, long seed, SimulatorParameters params,
            SimulationRunner.SimulationRunOutcome outcome, long graphSeed) {
        ExperimentRuntimeSnapshot s = outcome.snapshot();
        long evaluations = CautiousWaitingPolicy.getEvaluationsTotal();
        long hardDenies = CautiousWaitingPolicy.getHardDenyFiredTotal();
        double abortsPerCompletion = s.groupsCompletedTotal() == 0 ? 0.0
                : s.restartsTotal() / (double) s.groupsCompletedTotal();
        double durationTicks = (double) params.simulationDurationSeconds * 1_000_000.0;
        double unitTicks = (double) (params.numberOfIoProcessors + params.numberOfCpuProcessors)
                * durationTicks;
        double util = unitTicks <= 0 ? 0.0
                : (outcome.result().totalSimulatedIOTime + outcome.result().totalSimulatedCPUTime) / unitTicks;

        // frac_blocked is emitted PER ROW, not just per cell: it is calibrated at the base cell
        // only, and every axis moves it - theta concentrates accesses, cores=1 removes concurrency
        // entirely, a high read-only fraction removes most write conflicts. A figure has to be
        // readable against the REALISED contention of each point, not the calibrated one.
        raw.printf(Locale.US, "%s,%s,%s,%s,%s,%d,%d,%.4f,%d,%.2f,%.6f,%.6f,%.6f,%.6f,%.6f,"
                + "%.6f,%.6f,%.4f,%.4f,%.6f,%.4f,%d,%s,%.4f,%d,%d%n",
                id.key(), cfg.panel(), cfg.label(), axis, fmt(x), seed, graphSeed,
                s.windowedThroughput(), s.completionsInWindow(), s.measurementWindowS(),
                s.windowedFractionTimeBlocked(), s.windowedFractionTimeWaitingRoom(),
                abortsPerCompletion, evaluations == 0 ? 0.0 : hardDenies / (double) evaluations,
                util,
                // REALISED values. theta and readOnlyTransactionFraction are knobs; these are
                // the measurements. Emitted PER SEED-ROW because the realised skew depends on the
                // footprint draw, so a per-cell mean would hide the spread the x-axis needs.
                s.realizedTop1pctAccessShare(), s.realizedTop10pctAccessShare(),
                s.meanDistinctInstancesTouched(), s.writesPerTxnMean(),
                s.readOnlyTxnFractionRealized(),
                // Realised counterpart to the schema-derived cus_per_group in the sidecar - the
                // latter is an expectation, this is what actually fired.
                s.groupsCompletedTotal() == 0 ? 0.0
                        : s.bookkeepersCreatedTotal() / (double) s.groupsCompletedTotal(),
                s.admittedParentsForAccess(),
                outcome.terminatedBy().name(),
                s.meanCusInFlight(), s.peakCusInFlight(), s.cusInFlightNegativeSamples());
        raw.flush();
    }

    /**
     * One row per cell.
     *
     * <p><b>{@code seeds_requested} and {@code seeds_used} are separate columns on purpose</b>
     * (report achieved, not configured). They diverge for two independent
     * reasons and a single {@code seeds} column would hide both:
     *
     * <ul>
     *   <li><b>requested rises</b> when a point is ESCALATED - the seed rule is point-targeted,
     *       so cells legitimately carry unequal n, and a reader must be able to see which ones
     *       without being told in prose;</li>
     *   <li><b>used falls below requested</b> when a seed hits an engine fault and its row is
     *       dropped to {@code _skipped.csv}.</li>
     * </ul>
     *
     * <p>Recorded as columns rather than a footnote because escalation is conditional on results
     * that do not exist when the plan is written, and because {@code seeds_used} JOINS against the
     * per-seed {@code sdr} spread in the raw rows - which is how a reader checks the dense-graph
     * explanation for a widened cell. A footnote joins against nothing.
     */
    /**
     * Build provenance, read from a resource STAMPED INTO THE JAR at package time.
     *
     * <p>The numbers have to be tied to a committed plan via {@code plan_sha} / {@code dirty}. On
     * a host that has the repository that is a {@code git rev-parse}; on a host that received a
     * scp'd jar and has no git, no checkout and possibly no network, there is nothing to ask. So
     * the answer is baked in at build time and travels inside the artifact that produces the
     * numbers - which is strictly better than asking git on the analysis machine afterwards,
     * because that machine's HEAD is not evidence about the jar that ran.
     *
     * <p><b>Absence is reported, not tolerated silently.</b> An unstamped jar writes
     * {@code PROVENANCE: UNSTAMPED} into the metadata file beside the data, so the warning travels
     * with the CSV rather than living in a terminal someone closed. There is deliberately no flag
     * to suppress it: a switch that turns off a provenance record is the same inversion as a flag
     * that disables an assertion the test exists to prove.
     */
    private static List<String> provenanceLines() {
        List<String> out = new ArrayList<>();
        try (var in = WorkloadAxisSweep.class.getResourceAsStream("/campaign-provenance.properties")) {
            if (in == null) {
                out.add("PROVENANCE: UNSTAMPED - this jar carries no build record, so these rows");
                out.add("  cannot be tied to a commit. Rebuild with scripts/campaign/build_campaign_jar.sh");
                out.add("  if the numbers are going into the paper.");
                return out;
            }
            var props = new Properties();
            props.load(in);
            out.add("PROVENANCE (stamped into the jar at build time):");
            for (String key : new TreeSet<>(props.stringPropertyNames())) {
                out.add("  " + key + " = " + props.getProperty(key));
            }
        } catch (IOException e) {
            out.add("PROVENANCE: UNREADABLE (" + msg(e) + ")");
        }
        return out;
    }

    /** See the seeds_requested/seeds_used note on writeCell. */
    private static void writeCellsMetadata(String path) {
        try (PrintWriter m = new PrintWriter(new FileWriter(path))) {
            for (String line : provenanceLines()) {
                m.println(line);
            }
            m.println();
            m.println("seeds_requested vs seeds_used");
            m.println("  seeds_requested rises where a theta point was ESCALATED: the seed rule is");
            m.println("  POINT-TARGETED (plan 0.10), so cells carry unequal n by design, not by");
            m.println("  accident. seeds_used falls below requested where a seed hit an engine");
            m.println("  fault - those rows are in the _skipped.csv with the message.");
            m.println();
            m.println("  Escalation triggers when a point fails to separate from the multiplier's");
            m.println("  flat region by 2 sigma at n=5. The separation test's denominator is the");
            m.println("  STANDARD ERROR, not the sample sd: conflating the two understates");
            m.println("  separation by sqrt(n), which is enough to invert a verdict.");
            m.println("  Cross-reference seeds_used against the per-seed sdr spread in");
            m.println("  the _raw.csv to check the dense-graph-draw explanation for a widened cell.");
        } catch (IOException e) {
            throw new IllegalStateException("cannot write cells metadata: " + path, e);
        }
    }

    private static void writeCell(PrintWriter cellOut, WorkloadCellId id,
            List<PanelConfig> appearances, String axis, double x, SimulatorParameters params,
            int seedsRequested, int okSeeds) {
        int p = params.totalModels * params.propertiesPerModel.get(0);
        int d = p * params.instancePoolSize;
        double realisedF = p == 0 ? 0.0 : Math.round(p * params.semanticOverlap) / (double) p;

        double realisedMu = 0.0;
        double pigeonhole = 0.0;
        // Mean CUs per group, and the service-demand ratio to the control that follows from it.
        //
        // This is the denominator of the INTERACTION MULTIPLIER:
        //     (control_tps / cell_tps) / service_demand_ratio
        // i.e. how much throughput a cell loses relative to how much extra WORK its cascades
        // actually demand. At the base cell that reads ~12 - CPRs cost an order of magnitude more
        // than their raw service demand - which separates "CPRs do more work" from "CPRs interact
        // badly with concurrency". A value near 1 anywhere in the grid means CPRs are just extra
        // work there and the interaction story does not apply.
        //
        // The quotient itself is computed in ANALYSIS, not here: the driver shuffles cells,
        // so the control row for this x-point may not have been written yet. Only the per-cell
        // ingredient belongs in this row.
        double cusPerGroup = 0.0;
        double serviceDemandRatio = 1.0;
        if (params.semanticOverlap > 0.0) {
            // Row 26's guard, at the point of USE. These params must already carry this cell's
            // first salted seed; a null here would silently redraw the graph from the wall clock.
            WorkloadAxisDefaults.assertSeedsAreRecorded(params);
            List<Model> pool = new ModelFactory(params.instancePoolSize)
                    .createModels(params.propertiesPerModel);
            // 8-arg form, passing alpha and the ordering flag from params. The 6-arg form was
            // used here and silently defaults to (alpha = null, acyclicTotalOrder = false) - i.e.
            // UNIFORM target selection with NO preferential attachment. Panel D's axis IS alpha,
            // so this gate was validating realised_mu and cus_per_group against a graph the
            // engine never builds, and Panel D's three lines would have been gated identically.
            CprSchema schema = new CprSchemaBuilder(pool, params.cprTopologyMode,
                    params.semanticOverlap, params.avgCprsPerResource, params.maxCprsPerResource,
                    params.semanticOverlapSeed, params.cprInitialAttractiveness,
                    params.cprAcyclicTotalOrder).build();
            CprGraphMetrics gm = CprGraphMetrics.from(schema, p);
            realisedMu = gm.sources() == 0 ? 0.0 : gm.edges() / (double) gm.sources();
            // Expected CUs per group from the schema: sources that fire x their out-degree, over
            // the transactions that actually carry writes. A CU is ONE resource against the
            // parent's txnLen, which is what makes the demand ratio so close to 1 while the
            // throughput ratio is not.
            double sourcesPerTxn = (1.0 - params.readOnlyTransactionFraction)
                    * Math.ceil(params.constantTransactionSize / 2.0) * params.semanticOverlap;
            cusPerGroup = sourcesPerTxn * realisedMu;
            serviceDemandRatio = (params.constantTransactionSize + cusPerGroup)
                    / (double) params.constantTransactionSize;
            long k = (long) gm.inDegreeNonzeroResources() * params.instancePoolSize;
            pigeonhole = k == 0 ? 0.0
                    : params.initialTransactions * params.semanticOverlap
                            * params.constantTransactionSize / (double) k;
        }

        // The schema build above is the expensive part and depends only on params, so it happens
        // once however many panels this config appears in.
        for (PanelConfig cfg : appearances) {
        cellOut.printf(Locale.US,
                "%s,%s,%s,%s,%s,%.4f,%.2f,%d,%s,%.4f,%s,%s,%.3f,%.3f,%d,%d,%d,%s,%d,%d,%d,%.4f,%.4f,%.6f,%.2f,%.4f,%.4f,%.2f,%d,%d,%s%n",
                id.key(), cfg.panel(), cfg.label(), axis, fmt(x),
                params.semanticOverlap, params.avgCprsPerResource, params.maxCprsPerResource,
                params.cprTopologyMode.name(), params.cprInitialAttractiveness,
                params.cprAcyclicTotalOrder, params.groupUnitAffinity, params.zipfTheta, params.readOnlyTransactionFraction,
                params.numberOfCpuProcessors, params.constantTransactionSize,
                params.initialTransactions,
                params.maxConcurrentRunning == null ? "derived" : params.maxConcurrentRunning.toString(),
                d, params.totalLocks, (int) Math.ceil(d / (double) params.totalLocks),
                realisedF, realisedMu, pigeonhole,
                WorkloadAxisDefaults.cuServiceToQuantum(params), cusPerGroup, serviceDemandRatio,
                WorkloadAxisDefaults.referenceCapacityTps(params.numberOfCpuProcessors,
                        params.constantTransactionSize),
                seedsRequested, okSeeds, okSeeds > 0 ? "OK" : "NO_SEEDS");
        }
        cellOut.flush();
    }

    private static String fmt(double x) {
        return x == Math.rint(x) ? String.valueOf((long) x) : String.format(Locale.US, "%.3f", x);
    }

    private static String msg(Throwable e) {
        String m = e.getMessage();
        return (m == null ? e.getClass().getSimpleName() : m).replace('"', '\'').replace('\n', ' ');
    }
}
