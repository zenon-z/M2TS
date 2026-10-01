package de.tum.tssimulator.experiments.support;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import de.tum.tssimulator.bootstrap.SimulationWatchdogException;
import de.tum.tssimulator.config.SimulatorParameters;
import de.tum.tssimulator.core.ModelFactory;
import de.tum.tssimulator.core.Transaction;
import de.tum.tssimulator.cpr.CprSchemaBuilder;
import de.tum.tssimulator.experiments.SimulationRunner.SimulationRunOutcome;
import de.tum.tssimulator.experiments.SimulationRunner;

/**
 * Runs the same SimulatorParameters across N seeds and reduces a caller-supplied metric to
 * mean/stddev/95% CI, catching SimulationWatchdogException per-seed without aborting the whole
 * sweep. Extends CprTopologyComparisonExperiment's single-fixed-seed override into a real
 * multi-seed loop, shared by all four E1-E4 experiment classes instead of duplicating
 * seed/CI logic four times.
 *
 * The 95% CI half-width uses the normal approximation (z=1.96) rather than a Student's-t
 * table - a reasonable simplification for the Controls section's N>=5 seeds per point, but not
 * exact for very small N.
 */
public class SeedSweepRunner {
    private static final double Z_95 = 1.96;

    private final SimulationRunner runner = new SimulationRunner();

    @FunctionalInterface
    public interface MetricExtractor {
        double extract(SimulationRunOutcome outcome);
    }

    @FunctionalInterface
    public interface RatioMetricExtractor {
        double[] extract(SimulationRunOutcome outcome); // returns [numerator, denominator]
    }

    /** Aggregated statistics for one metric across a seed sweep. */
    public record SweepStats(int seedCount, int successCount, int watchdogCount, int timeoutCount,
                              double mean, double stddev, double ci95) {

        public static final SweepStats EMPTY = new SweepStats(0, 0, 0, 0, 0.0, 0.0, 0.0);

        /** Every seed at this point hit the watchdog - report a WATCHDOG-status row rather than
         * a mean over zero samples. */
        public boolean allWatchdog() {
            return seedCount > 0 && successCount == 0 && timeoutCount == 0;
        }
        
        public boolean allWatchdogOrTimeout() {
            return seedCount > 0 && successCount == 0;
        }
    }

    /**
     * Run {@code baseParams} across {@code seeds}, extracting one metric per successful seed
     * via {@code extractor} and reducing to mean/stddev/CI. Each seed overrides both
     * transactionSamplingSeed and semanticOverlapSeed (so CPR-schema randomness and
     * transaction-sampling randomness both vary together across the sweep, matching the
     * Controls section's "N independent seeds" intent).
     */
    public SweepStats run(String runNamePrefix, SimulatorParameters baseParams, long[] seeds,
            MetricExtractor extractor) {
        List<Double> values = new ArrayList<>(seeds.length);
        int watchdogCount = 0;

        for (long seed : seeds) {
            SimulatorParameters seeded = withSeed(baseParams, seed);
            try {
                SimulationRunOutcome outcome = runner.runDetailed(
                        String.format(Locale.US, "%s_seed%d", runNamePrefix, seed), seeded);
                values.add(extractor.extract(outcome));
            } catch (SimulationWatchdogException e) {
                watchdogCount++;
            } finally {
                Transaction.resetMetrics();
            }
        }

        return summarize(seeds.length, values, watchdogCount, 0);
    }

    /**
     * Same as {@link #run} but exposes every successful seed's full outcome, for callers (e.g.
     * BaseReconcilerCapacityKneeExperiment's lambda_measured/lambda_predicted comparison) that
     * need more than one scalar per seed.
     */
    public List<SimulationRunOutcome> runRaw(String runNamePrefix, SimulatorParameters baseParams, long[] seeds) {
        List<SimulationRunOutcome> outcomes = new ArrayList<>(seeds.length);
        for (long seed : seeds) {
            SimulatorParameters seeded = withSeed(baseParams, seed);
            try {
                outcomes.add(runner.runDetailed(
                        String.format(Locale.US, "%s_seed%d", runNamePrefix, seed), seeded));
            } catch (SimulationWatchdogException e) {
                // Caller inspects outcomes.size() < seeds.length to detect watchdog losses.
            } finally {
                Transaction.resetMetrics();
            }
        }
        return outcomes;
    }

    /**
     * F3 (VLDB reviewer response): same as {@link #runRaw} but never drops a seed. Every seed
     * produces exactly one SimulationRunOutcome - a watchdog-hung seed comes back tagged
     * terminatedBy=WATCHDOG with a best-effort partial snapshot instead of vanishing from the
     * batch. Use this (not {@link #runRaw}) for the raw-per-seed-row experiments (E-A..E-E),
     * where a censored run must still be a row, not a silent gap.
     */
    public List<SimulationRunOutcome> runRawWithCensoring(String runNamePrefix, SimulatorParameters baseParams,
            long[] seeds) {
        List<SimulationRunOutcome> outcomes = new ArrayList<>(seeds.length);
        for (long seed : seeds) {
            SimulatorParameters seeded = withSeed(baseParams, seed);
            try {
                outcomes.add(runner.runDetailedAllowPartial(
                        String.format(Locale.US, "%s_seed%d", runNamePrefix, seed), seeded));
            } finally {
                Transaction.resetMetrics();
            }
        }
        return outcomes;
    }

    /**
     * The shared per-seed run/log loop for the E-A..E-E raw-row experiments (D1: "one raw row
     * per seed-run, never pre-aggregate"). For each seed: runs via
     * {@link SimulationRunner#runDetailedAllowPartial}, builds one {@link RawRunRow} from the
     * outcome plus the per-config constants in {@code ctx}, and writes it immediately via
     * {@code writer} - so a crash partway through a sweep doesn't lose already-collected seeds.
     * Never drops a seed: a watchdog-hung seed still produces a row (terminatedBy=WATCHDOG).
     *
     * throughput/completions/the three mechanism fractions use the WINDOWED (F4) getters, so
     * they already reflect warmupSeconds if the caller's baseParams sets one (0 by default,
     * which makes the windowed getters equivalent to the whole-run values).
     */
    public void runAndWriteRawRows(RawRunRowWriter writer, String runNamePrefix, SimulatorParameters baseParams,
            long[] seeds, RawRunRowContext ctx) {
        runAndWriteRawRows(writer, runNamePrefix, baseParams, seeds, ctx, null);
    }

    @FunctionalInterface
    public interface PerSeedOutcomeListener {
        void onOutcome(long seed, SimulationRunOutcome outcome);
    }

    /**
     * Same as {@link #runAndWriteRawRows(RawRunRowWriter, String, SimulatorParameters, long[],
     * RawRunRowContext)}, plus an optional callback invoked with each seed's full outcome right
     * after its row is written - for callers (E-A's throughput-trace dump) that need something
     * beyond the RawRunRow columns without duplicating this method's run/write loop.
     */
    public void runAndWriteRawRows(RawRunRowWriter writer, String runNamePrefix, SimulatorParameters baseParams,
            long[] seeds, RawRunRowContext ctx, PerSeedOutcomeListener onOutcome) {
        for (long seed : seeds) {
            SimulatorParameters seeded = withSeed(baseParams, seed);
            try {
                SimulationRunOutcome outcome = runner.runDetailedAllowPartial(
                        String.format(Locale.US, "%s_seed%d", runNamePrefix, seed), seeded);
                writer.write(toRawRunRow(outcome, seed, seeded, ctx));
                if (onOutcome != null) {
                    onOutcome.onOutcome(seed, outcome);
                }
            } finally {
                Transaction.resetMetrics();
            }
        }
    }

    private static RawRunRow toRawRunRow(SimulationRunOutcome outcome, long seed, SimulatorParameters seededParams,
            RawRunRowContext ctx) {
        var snapshot = outcome.snapshot();
        var lockWait = snapshot.lockWaitStats();
        return RawRunRow.builder()
                .configId(ctx.configId())
                .f(ctx.f())
                .mu(ctx.mu())
                .m(ctx.m())
                .topology(ctx.topology())
                .mpl(ctx.mpl())
                .k(ctx.k())
                .instancePoolSize(ctx.instancePoolSize())
                .propertiesPerModel(ctx.propertiesPerModel())
                .totalModels(ctx.totalModels())
                .d(ctx.d())
                .kEffStatic(ctx.kEffStatic())
                // derived from seededParams (the run's actual, fully-materialized
                // SimulatorParameters), never from ctx (the caller's intent) - see
                // EffectiveConfigHasher's javadoc. configHash lets D2 assert every row sharing a
                // config_id actually shares a config; closureSize is an INDEPENDENT recomputation
                // from the same seededParams (not threaded from ctx.kEffStatic()'s call site), so
                // a caller/actual mismatch shows up as ctx.kEffStatic() vs
                // (closureSize + effective transaction size) disagreeing, not just as a missing
                // value.
                .configHash(EffectiveConfigHasher.hash(seededParams))
                .processorCount(Math.min(seededParams.numberOfIoProcessors, seededParams.numberOfCpuProcessors))
                .closureSize(recomputeClosureSize(seededParams))
                .seed(seed)
                .throughput(snapshot.windowedThroughput())
                .completions(snapshot.completionsInWindow())
                .measurementWindowS(snapshot.measurementWindowS())
                .fracBlocked(snapshot.windowedFractionTimeBlocked())
                .fracWaitingRoom(snapshot.windowedFractionTimeWaitingRoom())
                .wastedCpuIoFrac(snapshot.windowedWastedCpuIoFraction(
                        seededParams.numberOfIoProcessors, seededParams.numberOfCpuProcessors))
                // ExperimentRuntimeSnapshot.totalAbortedGroups()/abortsPerCompletion()
                // are misleadingly named - totalAbortedGroups() is wired from
                // SimulationMetrics.getTotalAborted(), a MEMBER-level counter (parent AND every
                // bookkeeper individually, via TransactionLifecycleManager.abort()), not a
                // group-level one. Confirmed empirically: a 40000s diagnostic run showed
                // totalAbortedGroups()=8187 vs restartsTotal()=2029 (~4.03x), and restartsTotal
                // IS genuinely group-level (one increment per completeRollback() call, alongside
                // the real SimulationMetrics.groupsAbortedTotal field). Left
                // ExperimentRuntimeSnapshot.abortsPerCompletion()/totalAbortedGroups() themselves
                // unchanged - pre-existing E1-E4 experiments may depend on their current
                // (member-level) behavior - and instead compute genuinely group-level values
                // here from restartsTotal(), matching what Decision 7 in RawRunRow's schema doc
                // actually promises ("aborts_total: group-level, matching aborts_per_completion's
                // denominator").
                .abortsPerCompletion(snapshot.groupsCompletedTotal() == 0 ? 0.0
                        : snapshot.restartsTotal() / (double) snapshot.groupsCompletedTotal())
                .abortsTotal(snapshot.restartsTotal())
                .restartsTotal(snapshot.restartsTotal())
                .lockWaitP50(lockWait != null ? lockWait.p50 : 0.0)
                .lockWaitP95(lockWait != null ? lockWait.p95 : 0.0)
                .lockWaitP99(lockWait != null ? lockWait.p99 : 0.0)
                .resourcesPerLogicalTxnMean(snapshot.meanStartedGroupParentResourceReferences())
                .terminatedBy(outcome.terminatedBy().name())
                .runWallS(outcome.result().wallClockTime / 1000.0)
                .warmupDiscardedS(snapshot.warmupDiscardedS())
                .build();
    }

    /** recomputes mean closure size directly from seededParams (the run's
     * actual, fully-materialized parameters) rather than trusting a caller-supplied value -
     * closure size is schema-level (models/properties/f/mu/m/topology), independent of MPL, so
     * this is safe and cheap to recompute per row (no simulation involved, just the same
     * CprSchemaBuilder BFS KEffStaticCalculator already runs once per config). Uses
     * semanticOverlapSeed for reproducibility, matching how the schema was actually built for
     * this run. */
    private static double recomputeClosureSize(SimulatorParameters seededParams) {
        var modelPool = new ModelFactory(seededParams.instancePoolSize)
                .createModels(seededParams.propertiesPerModel);
        var schema = new CprSchemaBuilder(modelPool, seededParams.cprTopologyMode,
                seededParams.semanticOverlap, seededParams.avgCprsPerResource, seededParams.maxCprsPerResource,
                seededParams.semanticOverlapSeed != null ? seededParams.semanticOverlapSeed : 0L,
                // Alpha and the ordering flag come from PARAMS, never from the 6-arg overload's
                // defaults (alpha = null => uniform targets): those would recompute closure_size
                // on a graph with NO preferential attachment while the engine ran one with it, and
                // closure size is precisely what alpha changes. All three alpha lines would then
                // report the same closure_size in every
                // RawRunRow. The javadoc above says "the run's actual, fully-materialized
                // parameters"; it now is.
                seededParams.cprInitialAttractiveness, seededParams.cprAcyclicTotalOrder)
                .build();
        return KEffStaticCalculator.meanClosureSize(modelPool, schema);
    }

    /**
     * Reduce an already-collected batch of outcomes (e.g. from {@link #runRaw}) to mean/stddev/CI
     * for one metric, without re-running the simulation. Use this instead of calling {@link #run}
     * once per metric when several metrics are needed from the same seed sweep - each call to
     * {@link #run} re-executes all N seeds, which is wasteful when the outcomes are already in
     * hand.
     */
    public static SweepStats summarizeMetric(List<SimulationRunOutcome> outcomes, int seedCount,
            MetricExtractor extractor) {
        List<Double> values = new ArrayList<>(outcomes.size());
        for (SimulationRunOutcome outcome : outcomes) {
            values.add(extractor.extract(outcome));
        }
        int watchdogCount = Math.max(0, seedCount - outcomes.size());
        return summarize(seedCount, values, watchdogCount, 0);
    }

    public static SweepStats summarizeRatioMetric(List<SimulationRunOutcome> outcomes, int seedCount,
            RatioMetricExtractor extractor) {
        int watchdogCount = Math.max(0, seedCount - outcomes.size());
        
        double sumNum = 0.0;
        double sumDen = 0.0;
        int timeoutCount = 0;
        List<Double> validSeedRatios = new ArrayList<>(outcomes.size());
        
        for (SimulationRunOutcome outcome : outcomes) {
            double[] nd = extractor.extract(outcome);
            double num = nd[0];
            double den = nd[1];
            
            if (den == 0.0) {
                timeoutCount++;
            } else {
                sumNum += num;
                sumDen += den;
                validSeedRatios.add(num / den);
            }
        }
        
        int validCount = validSeedRatios.size();
        if (validCount == 0) {
            return new SweepStats(seedCount, 0, watchdogCount, timeoutCount, 0.0, 0.0, 0.0);
        }
        
        double mean = sumNum / sumDen; // Ratio of means
        
        double sumSquaredDiff = 0.0;
        for (double r : validSeedRatios) {
            double diff = r - mean;
            sumSquaredDiff += diff * diff;
        }
        
        double stddev = validCount > 1 ? Math.sqrt(sumSquaredDiff / (validCount - 1)) : 0.0;
        double ci95 = validCount > 1 ? Z_95 * stddev / Math.sqrt(validCount) : 0.0;
        
        return new SweepStats(seedCount, validCount, watchdogCount, timeoutCount, mean, stddev, ci95);
    }

    private static SweepStats summarize(int seedCount, List<Double> values, int watchdogCount, int timeoutCount) {
        int n = values.size();
        if (n == 0) {
            return new SweepStats(seedCount, 0, watchdogCount, timeoutCount, 0.0, 0.0, 0.0);
        }
        double sum = 0.0;
        for (double v : values) {
            sum += v;
        }
        double mean = sum / n;

        double sumSquaredDiff = 0.0;
        for (double v : values) {
            double diff = v - mean;
            sumSquaredDiff += diff * diff;
        }
        // Sample stddev (n-1 denominator); 0 for a single successful seed (no variance estimate).
        double stddev = n > 1 ? Math.sqrt(sumSquaredDiff / (n - 1)) : 0.0;
        double ci95 = n > 1 ? Z_95 * stddev / Math.sqrt(n) : 0.0;

        return new SweepStats(seedCount, n, watchdogCount, timeoutCount, mean, stddev, ci95);
    }

    /**
     * Default seed set for a sweep point: >=5 seeds per the Controls section. Deterministic
     * (not System.nanoTime-derived) so re-running an experiment reproduces the same seed set.
     */
    public static long[] defaultSeeds(int count) {
        long[] seeds = new long[count];
        for (int i = 0; i < count; i++) {
            seeds[i] = 1_000_003L * (i + 1); // arbitrary large prime stride, avoids small-seed correlation
        }
        return seeds;
    }

    /**
     * {@link #defaultSeeds(int)} returns the SAME sequence for every caller, and
     * E-A..E-E all called it once per curve and reused that one seed array across every MPL
     * point in the curve. That means the same transactionSamplingSeed/semanticOverlapSeed pair
     * drives every MPL point's seed[i] - identical CPR-graph realization and identical
     * transaction-arrival draw shape, just replayed at a different admission concurrency. That
     * induces real cross-MPL correlation in the residuals along a curve (the exact "same luck"
     * carries through), which D3's Hansen inverted-LR interval assumes is absent (its asymptotic
     * theory assumes independent - or at least not-strongly-dependent - observations). Fresh,
     * decorrelated seeds per (config, mpl) cell fix this for free (deterministic salting, not
     * real randomness, so runs stay reproducible) - use this instead of {@link #defaultSeeds}
     * for any experiment sweeping mpl within a curve. cellSalt should be a stable function of
     * every OTHER key column (f, mu, m, topology, mpl, k, D) so cells never collide.
     */
    public static long[] saltedSeeds(int count, long cellSalt) {
        long[] seeds = new long[count];
        for (int i = 0; i < count; i++) {
            // Two independent large primes for the per-index stride and the per-cell salt
            // multiplier, so neither factor can accidentally align into a short cycle.
            seeds[i] = 1_000_003L * (i + 1) + 998_244_353L * cellSalt;
        }
        return seeds;
    }

    /** Deterministic, stable combination of a config cell's key fields into a salt for
     * {@link #saltedSeeds}. Manual bit-mixing rather than Object.hash()/List.hashCode(), which
     * carry no cross-JVM-version stability guarantee and would silently break reproducibility of
     * "the same config always gets the same seeds" across a Java upgrade. */
    /**
     * Salt from a canonical cell key, for sweeps whose axes the positional overload has no slot
     * for.
     *
     * <p>The seven-argument form below covers (f, mu, m, topology, mpl, k, d) and nothing else, so
     * a sweep over - say - access skew, read-only mix or transaction length would hand identical
     * arguments for cells that differ, and {@link #saltedSeeds} would replay the SAME draw at
     * every point of the new axis. That is the independence requirement failing silently: the
     * curve would still be drawn, and the residual correlation along the axis would be invisible.
     *
     * <p>Mixed character-by-character rather than via {@code String.hashCode()} so the value cannot
     * depend on anything but the characters, matching the positional form's reason for hand-rolling
     * its mix instead of calling {@code Objects.hash}.
     *
     * @param canonicalCellKey every axis of the cell, in a fixed order, arm variable EXCLUDED
     *                         (arms must be paired, so the arm must not enter the salt)
     */
    public static long cellSalt(String canonicalCellKey) {
        long h = 1_125_899_906_842_597L; // same prime seed as the positional form
        if (canonicalCellKey != null) {
            for (int i = 0; i < canonicalCellKey.length(); i++) {
                h = 31 * h + canonicalCellKey.charAt(i);
            }
        }
        return h;
    }

    public static long cellSalt(double f, double mu, int m, String topology, int mpl, int k, int d) {
        long h = 1_125_899_906_842_597L; // large prime seed for the mix
        h = 31 * h + Double.doubleToLongBits(f);
        h = 31 * h + Double.doubleToLongBits(mu);
        h = 31 * h + m;
        h = 31 * h + (topology == null ? 0 : topology.hashCode());
        h = 31 * h + mpl;
        h = 31 * h + k;
        h = 31 * h + d;
        return h;
    }

    /**
     * Drives BOTH seeds from the one sweep seed, so any semanticOverlapSeed or
     * transactionSamplingSeed already set on {@code base} is DISCARDED. 20 experiments set one of
     * them and route through here; for 19 that is harmless (they pin a constant, and this pins a
     * different one), but a caller that means the two seeds to move INDEPENDENTLY gets silence
     * rather than an error - V2 (z41) lost a 15-run probe to exactly that.
     *
     * <p>Deliberately NOT changed to honour the caller's value: that would alter the realized
     * topology of all 19 others and invalidate results already collected. Callers needing
     * decorrelated seeds use {@link #saltedSeeds(int, long)}; callers offering a seed knob should
     * reject it explicitly, as TgsPanelBSimCheck now does.
     */
    private static SimulatorParameters withSeed(SimulatorParameters base, long seed) {
        return copyBuilder(base)
                .transactionSamplingSeed(seed)
                .semanticOverlapSeed(seed)
                .build();
    }

    /** Full-field copy, including admissionBatchSize - unlike the copyBuilder() duplicated in
     * CprImpactExperimentSuite/CprTopologyComparisonExperiment, this one must preserve k since
     * every E1-E4 experiment relies on CprMplExperimentDefaults' non-default admissionBatchSize.
     * Public so the E1-E4 experiment classes share this one copy instead of redefining their
     * own (as the pre-existing Option B experiments each do). */
    public static SimulatorParameters.Builder copyBuilder(SimulatorParameters params) {
        return SimulatorParameters.builder()
                .totalModels(params.totalModels)
                .totalResources(params.totalResources)
                // propertiesPerModel AND instancePoolSize must be copied, and their absence is
                // detectable only as a suspiciously identical A/B comparison:
                // SimulatorParameters.Builder.build() silently falls back to
                // defaultPropertiesPerModel(totalModels)/defaultInstancePoolSize(...) whenever
                // these are unset (not an error - by design, for legacy totalResources-only
                // callers), every copyBuilder(base) call on a config with a CUSTOM model shape
                // (e.g. FootprintABExperiment's Config B: 30 models x 10 props, instancePoolSize
                // explicitly tuned) silently reverted to the 5-properties-per-model/derived-pool
                // default instead of preserving the caller's shape. This corrupted every per-MPL
                // rebuild in FootprintABExperiment.runConfig() (copyBuilder(base) inside the MPL
                // loop) - Config B was never actually simulating the calibrated ~98-closure graph
                // it was built to be, only ever the accidental default (~49-closure, D=4950
                // instead of the intended D=4800). Any other caller relying on copyBuilder() to
                // preserve a custom propertiesPerModel/instancePoolSize was equally affected.
                .propertiesPerModel(params.propertiesPerModel)
                .instancePoolSize(params.instancePoolSize)
                .ioTimePerResource(params.ioTimePerResource)
                .cpuTimePerResource(params.cpuTimePerResource)
                .lockIOTimePerResource(params.lockIOTimePerResource)
                .lockCPUTimePerResource(params.lockCPUTimePerResource)
                .rollbackFixed(params.rollbackFixed)
                .rollbackCPUTimePerResource(params.rollbackCPUTimePerResource)
                .initialTransactions(params.initialTransactions)
                .totalTransactionSteps(params.totalTransactionSteps)
                .maxTransactionModels(params.maxTransactionModels)
                .maxTransactionResources(params.maxTransactionResources)
                .useConstantTransactionSize(params.useConstantTransactionSize)
                .constantTransactionSize(params.constantTransactionSize)
                .transactionSizeProfile(params.transactionSizeProfile)
                .smallTransactionSize(params.smallTransactionSize)
                .largeTransactionSize(params.largeTransactionSize)
                .largeTransactionProbability(params.largeTransactionProbability)
                .useRandomResourceSelection(params.useRandomResourceSelection)
                .semanticOverlap(params.semanticOverlap)
                .maxCprsPerResource(params.maxCprsPerResource)
                .avgCprsPerResource(params.avgCprsPerResource)
                .cprTopologyMode(params.cprTopologyMode)
                .referentialAction(params.referentialAction)
                .concurrencyModel(params.concurrencyModel)
                .admissionBatchSize(params.admissionBatchSize)
                .simulationDurationSeconds(params.simulationDurationSeconds)
                .warmupSeconds(params.warmupSeconds)
                .numberOfIoProcessors(params.numberOfIoProcessors)
                .numberOfCpuProcessors(params.numberOfCpuProcessors)
                .numberOfReconcilerProcessors(params.numberOfReconcilerProcessors)
                .totalLocks(params.totalLocks)
                .transactionSamplingSeed(params.transactionSamplingSeed)
                .semanticOverlapSeed(params.semanticOverlapSeed)
                .stepTimeSlice(params.stepTimeSlice)
                .transactionMode(params.transactionMode)
                .numTerminals(params.numTerminals)
                .meanInterArrivalDelay(params.meanInterArrivalDelay)
                .transactionGenerationMode(params.transactionGenerationMode)
                .oracleClosurePreclaim(params.oracleClosurePreclaim)
                .oracleClosurePlacement(params.oracleClosurePlacement)
                .lockPoolSize(params.lockPoolSize)
                // Manual full-field copy: a field omitted here is silently dropped between the
                // caller's parameters and the seed sweep that actually runs. transactionModelIndices
                // was omitted on its first pass, and the symptom was a fire count that looked like
                // the UNPINNED arithmetic (k/2 * fraction) with no error anywhere.
                .transactionModelIndices(params.transactionModelIndices)
                // S2PL/deadlock-policy follow-up (same class of bug as propertiesPerModel/
                // instancePoolSize above): these four were missing from this copy list, so any
                // caller relying on copyBuilder(base) to preserve a non-default deadlockStrategy
                // (e.g. a per-mpl sweep like DynamicFootprintExperiment.runConfigSweep()) would
                // silently revert to the Builder's default (CAUTIOUS_WAITING) on every rebuild.
                .deadlockStrategy(params.deadlockStrategy)
                .deadlockDetectionInterval(params.deadlockDetectionInterval)
                .deadlockDetectionBetaThreshold(params.deadlockDetectionBetaThreshold)
                .deadlockDetectionMinCooldown(params.deadlockDetectionMinCooldown)
                // Third instance of the exact same bug (found while planning operation-level
                // SS2PL execution): lockingProtocol was missing here too. DynamicFootprintExperiment
                // .main() sets it on the base params, but runConfigSweep() rebuilds per-MPL through
                // this method - so every actually-executed run in the s2pl and s2pl-oracle arms
                // silently reverted to the Builder's default (C2PL). Every committed S2PL result
                // predating this line was therefore C2PL data. See LockingProtocol's javadoc: this
                // is the same failure the ENABLE_C2PL static was removed to prevent.
                .lockingProtocol(params.lockingProtocol)
                .writeFraction(params.writeFraction)
                .cuOperationType(params.cuOperationType)
                .writeOnlyCprSources(params.writeOnlyCprSources)
                .operationBatchSize(params.operationBatchSize)
                .groupUnitAffinity(params.groupUnitAffinity)
                // W1-W4 workload axes. Added together with a REFLECTION-DRIVEN exhaustiveness test
                // in SeedSweepRunnerCopyBuilderRoundTripTest, so the next field to go missing here
                // fails the build instead of silently corrupting a campaign - this list has now
                // lost four fields that way (propertiesPerModel/instancePoolSize, deadlockStrategy
                // +3, lockingProtocol, transactionModelIndices), each found only after the data
                // was already wrong.
                .accessDistribution(params.accessDistribution)
                .zipfTheta(params.zipfTheta)
                .workloadMix(params.workloadMix)
                .readOnlyTransactionFraction(params.readOnlyTransactionFraction)
                .maxConcurrentRunning(params.maxConcurrentRunning)
                .cprInitialAttractiveness(params.cprInitialAttractiveness)
                .backoffScale(params.backoffScale)
                .readyWaiterOrder(params.readyWaiterOrder)
                .cuQueuePolicy(params.cuQueuePolicy)
                .cprAcyclicTotalOrder(params.cprAcyclicTotalOrder);
    }
}
