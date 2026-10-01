package de.tum.tssimulator.experiments;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import de.tum.tssimulator.bookkeeping.CprGraphMetrics;
import de.tum.tssimulator.config.SimulatorParameters;
import de.tum.tssimulator.core.Model;
import de.tum.tssimulator.core.ModelFactory;
import de.tum.tssimulator.core.Property;
import de.tum.tssimulator.core.Transaction;
import de.tum.tssimulator.cpr.CprSchema;
import de.tum.tssimulator.cpr.CprSchemaBuilder;
import de.tum.tssimulator.experiments.support.ExperimentOutputPaths;
import de.tum.tssimulator.experiments.support.SeedSweepRunner;
import de.tum.tssimulator.experiments.support.WorkloadAxisDefaults;
import de.tum.tssimulator.experiments.support.WorkloadCellId;

/**
 * Scan of the CPR schema across the initial-attractiveness axis, for choosing the concentration
 * axis's three values.
 *
 * <h2>Why this must come first</h2>
 *
 * <p>The pigeonhole guard is {@code MPL*f*k / K < 0.2} with
 * {@code K = inDegreeNonzeroResources * instancePoolSize}. Small alpha concentrates in-degree onto
 * fewer distinct targets, so {@code inDegreeNonzeroResources} FALLS, {@code K} falls, and the
 * ratio RISES - at exactly the Panel D value that most needs to be feasible.
 *
 * <p>That is the guard working, not misfiring: concentrated in-degree genuinely does mean more
 * collisions on fewer targets, and the guard is what keeps collapse measured rather than forced.
 * So it cannot be relaxed. If a low-alpha cell breaches it, the remedy is a LARGER
 * {@code instancePoolSize} for that cell - which moves contention, which the pool calibration
 * controls. Panel D's values and the pool calibration are therefore COUPLED, not sequential, and
 * picking the alpha values first would silently invalidate the calibration done after them.
 *
 * <p>Everything here is a pure function of the schema - no simulation - so the whole scan costs
 * milliseconds and can be re-run whenever the geometry moves.
 *
 * <h2>Depth is reported, not set</h2>
 *
 * <p>Cascade depth falls out of {@code mu}, {@code m}, alpha and the model count; "setting" it
 * would mean capping the closure, which is an intervention rather than a parameter.
 *
 * <p><b>Depth is measured under the VISITED-SET rule, not as a longest path.</b> The campaign runs
 * with {@code cprAcyclicTotalOrder} off, so the schema may be cyclic and "longest path" is not a
 * defined quantity on it - nor is it the mechanism, since termination at runtime comes from
 * {@code CprSchema.computeAllClosures}' visited set. {@code depthStats} therefore BFSes from each
 * source, expanding each property at most once, which is what the engine does.
 */
public final class WorkloadAlphaScan {

    private static final double PIGEONHOLE_LIMIT = 0.2;

    private WorkloadAlphaScan() {
    }

    public static void main(String[] args) throws IOException {
        if (Boolean.getBoolean("ts.wl.thetaRange")) {
            thetaRangeCheck();
            return;
        }
        double campaignAlpha = Double.parseDouble(System.getProperty("ts.wl.campaignAlpha", "0"));
        if (campaignAlpha > 0) {
            campaignSeedCheck(campaignAlpha);
            return;
        }

        double[] alphas = parse(System.getProperty("ts.wl.alphas",
                "0.01,0.05,0.1,0.25,0.5,1,2,5,10,100,1000"));
        int pool = WorkloadAxisDefaults.INSTANCE_POOL_SIZE;
        double f = WorkloadAxisDefaults.BASE_SEMANTIC_OVERLAP;
        double mu = WorkloadAxisDefaults.BASE_MU;
        int m = WorkloadAxisDefaults.BASE_M;
        int mpl = WorkloadAxisDefaults.MPL;
        int k = WorkloadAxisDefaults.TRANSACTION_LENGTH;

        String path = ExperimentOutputPaths.resultsFile("wl_alpha_scan.csv");
        System.out.printf(Locale.US,
                "=== alpha scan: %s, f=%.2f mu=%.1f m=%d, pool=%d, MPL=%d k=%d ===%n",
                WorkloadAxisDefaults.BASE_TOPOLOGY, f, mu, m, pool, mpl, k);
        System.out.printf(Locale.US, "%8s %8s %9s %9s %8s %9s %9s %8s %8s %6s%n",
                "alpha", "sources", "real_mu", "in>0", "e/target", "max_in", "pigeon", "clo_mean",
                "depth_max", "gate");

        try (PrintWriter out = new PrintWriter(new FileWriter(path))) {
            out.println("alpha,sources,edges,realised_mu,in_degree_nonzero,in_degree_gini,"
                    + "max_in_degree,edges_per_distinct_target,reachable_key_space,pigeonhole_ratio,closure_mean,closure_max,"
                    + "depth_mean,depth_max,pigeonhole_pass");

            for (double alpha : alphas) {
                List<Model> models = new ModelFactory(pool).createModels(
                        Collections.nCopies(WorkloadAxisDefaults.TOTAL_MODELS,
                                WorkloadAxisDefaults.PROPERTIES_PER_MODEL));
                CprSchema schema = new CprSchemaBuilder(models, WorkloadAxisDefaults.BASE_TOPOLOGY,
                        f, mu, m, Long.getLong("ts.wl.graphSeed", 67890L), alpha,
                        totalOrder()).build();
                CprGraphMetrics gm = CprGraphMetrics.from(schema, WorkloadAxisDefaults.TOTAL_PROPERTIES);

                double realisedMu = gm.sources() == 0 ? 0.0 : gm.edges() / (double) gm.sources();
                long keySpace = (long) gm.inDegreeNonzeroResources() * pool;
                double pigeonhole = keySpace == 0 ? Double.POSITIVE_INFINITY
                        : WorkloadAxisDefaults.cprOffersPerBurst(mpl, f, k,
                                WorkloadAxisDefaults.BASE_READ_ONLY_FRACTION) / (double) keySpace;
                double[] depth = depthStats(schema);
                boolean pass = pigeonhole < PIGEONHOLE_LIMIT;

                System.out.printf(Locale.US,
                        "%8.2f %8d %9.3f %9d %8.3f %9d %9.4f %8.2f %8.0f %6s%n",
                        alpha, gm.sources(), realisedMu, gm.inDegreeNonzeroResources(),
                        gm.inDegreeNonzeroResources() == 0 ? 0.0
                                : gm.edges() / (double) gm.inDegreeNonzeroResources(),
                        gm.maxInDegree(), pigeonhole,
                        gm.meanTransitiveReachableResources(), depth[1], pass ? "ok" : "FAIL");
                out.printf(Locale.US, "%.4f,%d,%d,%.4f,%d,%.4f,%d,%.4f,%d,%.6f,%.4f,%d,%.4f,%.0f,%s%n",
                        alpha, gm.sources(), gm.edges(), realisedMu, gm.inDegreeNonzeroResources(),
                        gm.inDegreeGini(), gm.maxInDegree(),
                        gm.inDegreeNonzeroResources() == 0 ? 0.0
                                : gm.edges() / (double) gm.inDegreeNonzeroResources(),
                        keySpace, pigeonhole,
                        gm.meanTransitiveReachableResources(), gm.maxTransitiveReachableResources(),
                        depth[0], depth[1], pass);
            }
        }

        System.out.println();
        System.out.println("Pick Panel D's three alpha values from the rows that PASS the pigeonhole");
        System.out.println("gate, spanning as much in_gini (in-degree concentration) as those allow.");
        System.out.println("A low-alpha row that FAILS needs a larger instancePoolSize, which moves");
        System.out.println("contention - so re-run Phase 0.4 AFTER the values are chosen, not before.");
        System.out.println("\nWrote " + path);
    }

    /**
     * Does theta still have DYNAMIC RANGE at the current instance pool, and are the counters
     * actually wired?
     *
     * <p>Both questions in one pass, because they have the same failure mode: a realised share
     * that does not move across theta is indistinguishable from a counter that was never
     * connected. {@code admitted} being nonzero separates them.
     *
     * <p>The pool moved by an order of magnitude during calibration, and a LARGER instance space
     * should give the skew axis MORE range - top-1% becomes tens of instances rather than a
     * handful. But that is an inference about a sampler whose draw is without replacement, which
     * truncates the head exactly where it matters, so it is measured rather than assumed.
     */
    private static void thetaRangeCheck() {
        System.out.printf(Locale.US, "=== theta dynamic range at pool=%d (D=%d), MPL=%d ===%n",
                WorkloadAxisDefaults.INSTANCE_POOL_SIZE,
                WorkloadAxisDefaults.totalResources(WorkloadAxisDefaults.INSTANCE_POOL_SIZE),
                WorkloadAxisDefaults.MPL);
        double refCap = WorkloadAxisDefaults.referenceCapacityTps(
                WorkloadAxisDefaults.CORES, WorkloadAxisDefaults.TRANSACTION_LENGTH);
        System.out.printf(Locale.US, "%8s %12s %12s %14s %12s %10s %10s %10s %8s %8s%n",
                "theta", "top1%", "top10%", "distinct/txn", "writes/txn", "admitted",
                "tps", "cap_share", "sdr", "mult");
        // Overridable so a SINGLE theta can be run first-in-JVM. Running the whole list in one
        // process is exactly what the plan's "one JVM, one simulation" rule forbids, and this
        // property is what makes the contamination measurable rather than assumed.
        // The criterion in the campaign plan is stated "within measurement error", so it is not
        // checkable at n=1. -Dts.wl.thetaSeeds=N runs N seeds per theta and reports the
        // multiplier's mean and sd, which is what makes "approximately 1.00" and "clearly above"
        // decidable rather than eyeballed.
        //
        // Batching seeds in one JVM is safe ONLY because TransactionGroup.ID_GENERATOR is now
        // reset per run and SimulationRunReproducibilityTest pins that; before that fix this loop
        // would have aliased run position onto theta.
        int thetaSeeds = Integer.getInteger("ts.wl.thetaSeeds", 1);
        // Defaults to the CAMPAIGN's theta set rather than a second hardcoded copy. A literal
        // here goes stale the moment the axis is re-spaced, and every measurement taken with it
        // then avoids the stale set only by passing -Dts.wl.thetas explicitly. A diagnostic whose
        // default cell list differs from the thing it diagnoses is the same failure as a gate that
        // builds its subject differently from the system under test.
        String thetaSpec = System.getProperty("ts.wl.thetas");
        double[] thetaPoints = thetaSpec == null
                ? WorkloadAxisSweep.axisValues("theta")
                : parse(thetaSpec);
        for (double theta : thetaPoints) {
          double[] multSamples = new double[thetaSeeds];
          double[] shareSamples = new double[thetaSeeds];
          for (int seedIndex = 0; seedIndex < thetaSeeds; seedIndex++) {
            // -Dts.wl.f=0 runs the CONTROL arm (no CPR) across the same theta points. The control
            // is what decides whether the upper theta points are wasted: if it floors where the
            // CPR arm floors, every arm is dead there; if it holds while the CPR arm collapses,
            // the separation lives in the transition and the axis is well placed at the top.
            SimulatorParameters params = WorkloadAxisDefaults.baseBuilder()
                    .semanticOverlap(Double.parseDouble(System.getProperty("ts.wl.f",
                            String.valueOf(WorkloadAxisDefaults.BASE_SEMANTIC_OVERLAP))))
                    .zipfTheta(theta)
                    // -Dts.wl.txnLen: the heap probe needs the WORST cell, not a typical one.
                    // k=50 is the largest footprint and the most bookkeepers per group, and
                    // shard count is set by the worst cell's peak heap, not the average.
                    .constantTransactionSize(Integer.getInteger("ts.wl.txnLen",
                            WorkloadAxisDefaults.TRANSACTION_LENGTH))
                    .transactionSamplingSeed(1_000_003L * (seedIndex + 1))
                    .semanticOverlapSeed(1_000_003L * (seedIndex + 1))
                    .build();
            var outcome = new SimulationRunner()
                    .runDetailedAllowPartial("wl_theta_range_" + theta, params);
            var s = outcome.snapshot();
            // REALISED service demand ratio, not the schema-derived one. Every resource
            // reference costs the same (io + cpu = 2000 ticks) whether it belongs to a parent or
            // a CU, so the reference ratio IS the service ratio - and both counters are over
            // COMPLETED work, so a cascade that was generated but never ran is not charged.
            //
            // The schema figure (cus_per_group from the CPR graph) would be wrong here: writes
            // per transaction drift 2.51 -> 2.70 across theta, so realised CUs drift with it, and
            // a fixed schema SDR would push that drift into the multiplier - booking genuine
            // extra WORK as interaction COST, which is the one thing the multiplier must not do.
            double parentRefs = s.completedParentResourceReferencesTotal();
            double cuRefs = s.completedBookkeeperResourceReferencesTotal();
            double sdr = parentRefs <= 0 ? 1.0 : (parentRefs + cuRefs) / parentRefs;
            double capShare = s.windowedThroughput() / refCap;
            // Valid only because the control is capacity-saturated at every theta (measured):
            // capacity share IS the ratio to the control, so no paired control row is needed.
            double multiplier = capShare <= 0 ? Double.NaN : (1.0 / capShare) / sdr;

            System.out.printf(Locale.US,
                    "%8.2f %11.4f%% %11.4f%% %14.2f %12.2f %10d %10.1f %10.3f %8.3f %8.2f%n",
                    theta, 100.0 * s.realizedTop1pctAccessShare(),
                    100.0 * s.realizedTop10pctAccessShare(),
                    s.meanDistinctInstancesTouched(), s.writesPerTxnMean(),
                    s.admittedParentsForAccess(),
                    s.windowedThroughput(), capShare, sdr, multiplier);
            multSamples[seedIndex] = multiplier;
            shareSamples[seedIndex] = capShare;
            Transaction.resetMetrics();
          }
          if (thetaSeeds > 1) {
              System.out.printf(Locale.US,
                      "%8.2f  >>> over %d seeds: mult = %.3f +/- %.3f   cap_share = %.4f +/- %.4f%n",
                      theta, thetaSeeds, mean(multSamples), sd(multSamples),
                      mean(shareSamples), sd(shareSamples));
          }
        }
        System.out.println();
        System.out.println("top1% must RISE with theta. If it is flat, either the pool is too large");
        System.out.println("for the skew to bite or the counter is not wired - 'admitted' being");
        System.out.println("nonzero is what tells those two apart (C15).");
        System.out.printf(Locale.US, "distinct/txn is F4's saturation check - read it against k=%d.%n",
                WorkloadAxisDefaults.TRANSACTION_LENGTH);
    }

    /**
     * Run the pigeonhole gate at the campaign's ACTUAL seeds for one alpha, not at a convenient
     * sample of three.
     *
     * <p>Seeds are CELL-SPECIFIC: the driver uses
     * {@code saltedSeeds(n, WorkloadCellId.salt())}, and the cell key includes theta, read-only
     * fraction, cores and transaction length. So a single alpha config draws a DIFFERENT graph at
     * every x-point of every axis - 18 effective x-points x 5 seeds = 90 distinct graphs, any one
     * of which can breach. Three hand-picked seeds cannot speak for that set, and the observed
     * spread at alpha=0.05 was already 1.7x with its worst case at 72% of the limit.
     *
     * <p>If any cell fails, raise {@code instancePoolSize} for EVERY cell rather than for this
     * panel only: an alpha-specific pool would change D between Panel D's lines, making them
     * incomparable, which is precisely what the panel exists to avoid.
     */
    private static void campaignSeedCheck(double alpha) {
        int pool = WorkloadAxisDefaults.INSTANCE_POOL_SIZE;
        double f = WorkloadAxisDefaults.BASE_SEMANTIC_OVERLAP;
        double mu = WorkloadAxisDefaults.BASE_MU;
        int m = WorkloadAxisDefaults.BASE_M;
        int mpl = WorkloadAxisDefaults.MPL;
        int k = WorkloadAxisDefaults.TRANSACTION_LENGTH;
        int seedCount = Integer.getInteger("ts.wl.seeds", 5);

        System.out.printf(Locale.US,
                "=== campaign-seed pigeonhole check: alpha=%.4f, pool=%d, %d seeds/cell ===%n",
                alpha, pool, seedCount);

        double worst = 0.0;
        String worstCell = "";
        int fails = 0;
        int cells = 0;
        // The ratio scales INVERSELY with distinct targets, so the lower tail of that count over
        // all graphs is what decides the margin - a max-ratio summary can hide two alphas whose
        // worst graphs are equally concentrated. Tracked explicitly rather than inferred.
        int minTargets = Integer.MAX_VALUE;
        int maxTargets = 0;
        double sumTargets = 0.0;
        for (String axis : new String[] {"theta", "readonly", "cores", "txnlen"}) {
            for (double x : WorkloadAxisSweep.axisValues(axis)) {
                // Rebuild the cell key exactly as the driver does, so these are the real seeds.
                double theta = "theta".equals(axis) ? x : WorkloadAxisDefaults.BASE_THETA;
                double ro = "readonly".equals(axis) ? x : WorkloadAxisDefaults.BASE_READ_ONLY_FRACTION;
                int cores = "cores".equals(axis) ? (int) x : WorkloadAxisDefaults.CORES;
                int txnLen = "txnlen".equals(axis) ? (int) x : WorkloadAxisDefaults.TRANSACTION_LENGTH;
                WorkloadCellId id = new WorkloadCellId(f, mu, m,
                        WorkloadAxisDefaults.BASE_TOPOLOGY.name(), alpha, theta, ro, cores, txnLen);
                for (long seed : SeedSweepRunner.saltedSeeds(seedCount, id.salt())) {
                    List<Model> models = new ModelFactory(pool).createModels(
                            Collections.nCopies(WorkloadAxisDefaults.TOTAL_MODELS,
                                    WorkloadAxisDefaults.PROPERTIES_PER_MODEL));
                    CprSchema schema = new CprSchemaBuilder(models,
                            WorkloadAxisDefaults.BASE_TOPOLOGY, f, mu, m, seed, alpha,
                        totalOrder()).build();
                    CprGraphMetrics gm = CprGraphMetrics.from(schema,
                            WorkloadAxisDefaults.TOTAL_PROPERTIES);
                    long keySpace = (long) gm.inDegreeNonzeroResources() * pool;
                    // NOTE: MPL*f*k uses the CELL's transaction length, not the base one - the
                    // txnlen axis reaches k=50, which raises offers fivefold at constant K.
                    double ratio = keySpace == 0 ? Double.POSITIVE_INFINITY
                            : WorkloadAxisDefaults.cprOffersPerBurst(mpl, f, txnLen, ro)
                                    / (double) keySpace;
                    cells++;
                    int targets = gm.inDegreeNonzeroResources();
                    minTargets = Math.min(minTargets, targets);
                    maxTargets = Math.max(maxTargets, targets);
                    sumTargets += targets;
                    if (ratio > worst) {
                        worst = ratio;
                        worstCell = String.format(Locale.US, "axis=%s x=%s seed=%d in>0=%d",
                                axis, fmt(x), seed, gm.inDegreeNonzeroResources());
                    }
                    if (ratio >= PIGEONHOLE_LIMIT) {
                        fails++;
                        System.out.printf(Locale.US, "  FAIL %.4f  axis=%s x=%s seed=%d in>0=%d%n",
                                ratio, axis, fmt(x), seed, gm.inDegreeNonzeroResources());
                    }
                }
            }
        }
        System.out.printf(Locale.US, "%n%d cells checked, %d failed. Worst ratio %.4f (limit %.2f) at %s%n",
                cells, fails, worst, PIGEONHOLE_LIMIT, worstCell);
        System.out.printf(Locale.US, "margin at the worst cell: %.0f%% of the limit%n",
                100.0 * worst / PIGEONHOLE_LIMIT);
        System.out.printf(Locale.US,
                "distinct targets over all %d graphs: min=%d mean=%.1f max=%d  <- the LOWER TAIL "
                        + "is what sets the margin%n",
                cells, minTargets == Integer.MAX_VALUE ? 0 : minTargets,
                cells == 0 ? 0.0 : sumTargets / cells, maxTargets);
        if (fails > 0) {
            System.out.println("Raise instancePoolSize for EVERY cell, not just this panel - an");
            System.out.println("alpha-specific pool changes D between Panel D's lines and makes them");
            System.out.println("incomparable, which is the one thing the panel exists to avoid.");
        }
    }

    private static String fmt(double x) {
        return x == Math.rint(x) ? String.valueOf((long) x) : String.format(Locale.US, "%.3f", x);
    }

    /**
     * Longest-path depth over the schema DAG. Safe only because {@code cprAcyclicTotalOrder} makes
     * the graph acyclic by construction; memoised DFS would not terminate on a cycle.
     *
     * @return {mean depth over source properties, max depth}
     */
    /**
     * Cascade depth under the <b>visited-set</b> rule - the same rule
     * {@code CprSchema.computeAllClosures} applies at runtime.
     *
     * <p><b>This replaces a longest-path walk, which is now both wrong and unsafe.</b> The campaign
     * runs with {@code cprAcyclicTotalOrder} OFF (see {@code WorkloadAxisDefaults}), so the schema
     * may contain cycles. The previous implementation was unguarded memoised recursion over
     * successors: on a cyclic graph it recurses forever and dies with a StackOverflowError, and
     * even where it survived, "longest path" is not a defined quantity on a cyclic graph.
     *
     * <p>More importantly it would have measured the wrong thing. Depth is now bounded by the
     * visited set, not by structure, so a BFS that marks nodes visited - each node expanded at
     * most once, exactly as the runtime does - is the statistic that corresponds to what the
     * engine will actually do. Memoisation across sources is dropped deliberately: a visited-set
     * traversal's depth depends on where it STARTED, so a per-source cache would be answering a
     * different question for every node after the first.
     */
    private static double[] depthStats(CprSchema schema) {
        Map<Property, List<Property>> graph = schema.asGraph();
        double sum = 0.0;
        int max = 0;
        int n = 0;
        for (Property p : graph.keySet()) {
            int d = visitedSetDepth(p, graph);
            sum += d;
            max = Math.max(max, d);
            n++;
        }
        return new double[] {n == 0 ? 0.0 : sum / n, max};
    }

    /** BFS from one source, each property expanded at most once - terminates on any graph. */
    private static int visitedSetDepth(Property source, Map<Property, List<Property>> graph) {
        Set<Property> visited = new HashSet<>();
        Deque<Property> frontier = new ArrayDeque<>();
        visited.add(source);
        frontier.add(source);
        int depth = 0;
        while (!frontier.isEmpty()) {
            int levelSize = frontier.size();
            boolean advanced = false;
            for (int i = 0; i < levelSize; i++) {
                for (Property t : graph.getOrDefault(frontier.poll(), List.of())) {
                    if (visited.add(t)) {
                        frontier.add(t);
                        advanced = true;
                    }
                }
            }
            if (advanced) {
                depth++;
            }
        }
        return depth;
    }

    /**
     * Campaign default, overridable with {@code -Dts.wl.totalOrder=true} so the with/without
     * comparison can be measured rather than argued - the scan's numbers were originally taken
     * with the order ON, and every one of them had to be re-read when it came off.
     */
    private static double mean(double[] xs) {
        double t = 0.0;
        for (double x : xs) {
            t += x;
        }
        return xs.length == 0 ? 0.0 : t / xs.length;
    }

    private static double sd(double[] xs) {
        if (xs.length < 2) {
            return 0.0;
        }
        double m = mean(xs);
        double t = 0.0;
        for (double x : xs) {
            t += (x - m) * (x - m);
        }
        return Math.sqrt(t / (xs.length - 1));
    }

    private static boolean totalOrder() {
        String v = System.getProperty("ts.wl.totalOrder");
        return v == null ? WorkloadAxisDefaults.BASE_ACYCLIC_TOTAL_ORDER : Boolean.parseBoolean(v);
    }

    private static double[] parse(String spec) {
        List<Double> xs = new ArrayList<>();
        for (String tok : spec.split(",")) {
            if (!tok.isBlank()) {
                xs.add(Double.parseDouble(tok.trim()));
            }
        }
        double[] out = new double[xs.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = xs.get(i);
        }
        return out;
    }
}
