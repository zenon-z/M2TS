package de.tum.tssimulator.experiments.support;

import java.util.Locale;

/**
 * VLDB reviewer response, "Metrics to log / Per seed-run (raw rows, one per run - never
 * pre-aggregate)". One instance = one (config, seed) pair. All 5 new experiments (E-A..E-E)
 * write exactly this schema via {@link RawRunRowWriter} - aggregation (mean/CI/breakpoint fit)
 * happens only in the Python analysis pipeline (scripts/analysis/), never here.
 *
 * POPULATION / TERMINATION-PATH COVERAGE, traced per call site and checked against a live
 * diagnostic: peakBlockedQueueDepth reaches 390-502 at MPL=8, which is only possible if
 * bookkeepers populate the blocked queue too rather than just the 8 concurrently-admitted
 * parents.
 *
 *   - frac_blocked, frac_waiting_room: population = every in-flight Transaction (parent AND
 *     bookkeeper) currently sitting in ANY execution-phase queue (pending/lock/io/cpu/blocked/
 *     ready/awaitingUserAction/waitingForGroup) - see SimulationMetrics.recordHealthSnapshot().
 *     Numerator and denominator are drawn from the SAME population (both are *QueueDepth sums,
 *     never MPL and never a StateCount field, which would silently be parent-only) - NOT
 *     bookkeepers-in-numerator-vs-parents-in-denominator, which would inflate the fraction with
 *     f*mu by construction and make cross-R invariance uninterpretable. TIME-WEIGHTED (Sigma
 *     count*dt integrated at every recordHealthSnapshot() call), not event-counted - a count of
 *     blocking events and this occupancy fraction diverge once waits get long. Termination-path
 *     coverage: unbiased by construction - this is a continuous-time population integral, not a
 *     per-transaction average conditioned on eventually completing, so it cannot inherit a
 *     survival-selection bias. Confirmed the abort path (TransactionGroupAbortCoordinator.
 *     removeFromQueues(), via CentralQueueManager.remove()) strips a member from whatever queue
 *     it occupies BEFORE marking it aborted, so no stuck-queue artifact either.
 *   - wasted_cpu_io_frac: population = every group member (parent + all bookkeepers, all
 *     depths, including a defensive cross-group case) whose GROUP aborts - see
 *     TransactionGroupAbortCoordinator.transitionMembersToAborted()/recordWastedWork(), the
 *     sole call site. This is BY DESIGN an abort-only metric (work that completes is not
 *     "wasted") - confirmed complete coverage of the abort path, not a gap.
 *   - lock_wait_p50/p95/p99: population = every Transaction (parent or bookkeeper) that was
 *     ever blocked waiting for a lock, on EITHER termination path as of the review-round-3 fix -
 *     TransactionLockCoordinator.acquireAllLocks() records on successful acquisition,
 *     TransactionGroupAbortCoordinator.recordLockWaitOnAbort() records on abort (added because
 *     the abort path silently dropped every in-flight wait interval before that fix - see its
 *     javadoc). Before that fix this column was completion-path-only and could read near-zero
 *     in exactly the high-abort regime where it mattered most.
 *   - lock_seconds_waiting_room (F1, NOT in this schema, kept only as a secondary diagnostic on
 *     SimulationMetrics): population = PARENT TRANSACTIONS ONLY, and only those admitted via
 *     ACIDAdmissionController.tryAdmitWithLockCheck() - bookkeepers never reach that call site
 *     on either admission branch. This is the one metric in the whole instrumentation set that
 *     really is scoped to a strict subset of the population - see
 *     SimulationMetrics.getLockSecondsWaitingRoomFraction()'s javadoc. Deliberately excluded
 *     from this schema for exactly that reason.
 *   - aborts_total, restarts_total: group-level, incremented once per aborting GROUP in
 *     TransactionGroupAbortCoordinator.completeRollback(), triggered by whichever member
 *     (parent or bookkeeper) caused the abort - not completion-conditioned at all (these are
 *     the abort-path counters, the complement of throughput/completions). These must NOT be
 *     sourced from ExperimentRuntimeSnapshot.totalAbortedGroups()/abortsPerCompletion(), which
 *     despite their names are wired from SimulationMetrics.getTotalAborted() - a MEMBER-level
 *     counter (parent AND every bookkeeper individually), not group-level. Measured on a 40000s
 *     diagnostic run, totalAbortedGroups()=8187 against the genuinely group-level
 *     restartsTotal()=2029, ~4x, consistent with average group fan-out at abort time. Both
 *     columns are therefore sourced
 *     from restartsTotal() instead, which really is one increment per aborting group.
 *     ExperimentRuntimeSnapshot.abortsPerCompletion()/totalAbortedGroups() themselves were left
 *     unchanged (pre-existing E1-E4 experiments may depend on their current member-level
 *     behavior) - this fix is scoped to the E-A..E-E raw-row schema only.
 *   - throughput, completions: by definition completion-only (that is what they measure) - not
 *     a bias, the actual measurement target. Windowed (F4) via
 *     SimulationMetrics.getWindowedThroughput()/getCompletionsInWindow().
 *   - resources_per_logical_txn_mean: recorded at GROUP START
 *     (TransactionGroupRegistry.registerParent() -> recordStartedGroupParentResources()), not
 *     conditioned on the group's eventual fate - unbiased by termination path.
 *   - k_eff_static: config-time analytical computation from the CPR schema's closures, no
 *     runtime/per-transaction dependency at all.
 */
public final class RawRunRow {
    // Join keys / config-level fields (constant across every seed of one config).
    public final String configId;
    public final double f;
    public final double mu;
    public final int m;
    public final String topology;
    public final int mpl;
    public final int k;
    public final int instancePoolSize;
    public final int propertiesPerModel;
    public final int totalModels;
    public final int d; // total resources
    public final double kEffStatic;
    // config-integrity follow-up: config_hash/processor_count/closure_size are
    // ALL derived from the run's actual, fully-materialized SimulatorParameters at the
    // SeedSweepRunner.toRawRunRow() call site - never caller-supplied - specifically so a
    // caller-vs-actual mismatch (like the copyBuilder() bug that motivated this) shows up in the
    // data itself instead of requiring a human to notice a suspicious coincidence. See
    // EffectiveConfigHasher's javadoc for the incident this exists to catch mechanically going
    // forward: D2 asserts every row sharing a config_id shares a config_hash, and distinct
    // config_ids never collide.
    public final String configHash;
    public final int processorCount;
    public final double closureSize;

    // Seed-level fields. See the class javadoc above for each field's exact population and
    // termination-path coverage - do not assume "whole group" or "unconditional" without
    // checking it there.
    public final long seed;
    public final double throughput;
    public final int completions;
    public final double measurementWindowS;
    public final double fracBlocked;
    public final double fracWaitingRoom;
    public final double wastedCpuIoFrac;
    public final double residualFrac;
    public final double abortsPerCompletion;
    public final int abortsTotal;
    public final int restartsTotal;
    public final double lockWaitP50;
    public final double lockWaitP95;
    public final double lockWaitP99;
    public final double resourcesPerLogicalTxnMean;
    public final String terminatedBy;
    public final double runWallS;
    public final double warmupDiscardedS;

    private RawRunRow(Builder b) {
        this.configId = b.configId;
        this.f = b.f;
        this.mu = b.mu;
        this.m = b.m;
        this.topology = b.topology;
        this.mpl = b.mpl;
        this.k = b.k;
        this.instancePoolSize = b.instancePoolSize;
        this.propertiesPerModel = b.propertiesPerModel;
        this.totalModels = b.totalModels;
        this.d = b.d;
        this.kEffStatic = b.kEffStatic;
        this.configHash = b.configHash;
        this.processorCount = b.processorCount;
        this.closureSize = b.closureSize;
        this.seed = b.seed;
        this.throughput = b.throughput;
        this.completions = b.completions;
        this.measurementWindowS = b.measurementWindowS;
        this.fracBlocked = b.fracBlocked;
        this.fracWaitingRoom = b.fracWaitingRoom;
        this.wastedCpuIoFrac = b.wastedCpuIoFrac;
        this.abortsPerCompletion = b.abortsPerCompletion;
        this.abortsTotal = b.abortsTotal;
        this.restartsTotal = b.restartsTotal;
        this.lockWaitP50 = b.lockWaitP50;
        this.lockWaitP95 = b.lockWaitP95;
        this.lockWaitP99 = b.lockWaitP99;
        this.resourcesPerLogicalTxnMean = b.resourcesPerLogicalTxnMean;
        this.terminatedBy = b.terminatedBy;
        this.runWallS = b.runWallS;
        this.warmupDiscardedS = b.warmupDiscardedS;
        // residual_frac is always derived, never caller-supplied - see RawRunRowWriter's
        // decomposition-sum gate, which this makes trivially satisfiable by construction unless
        // the three mechanism fractions themselves are inconsistent (e.g. >1 individually).
        this.residualFrac = 1.0 - fracBlocked - fracWaitingRoom - wastedCpuIoFrac;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static String csvHeader() {
        return "config_id,seed,f,mu,m,topology,mpl,k,instance_pool_size,properties_per_model,"
                + "total_models,D,throughput,completions,measurement_window_s,frac_blocked,"
                + "frac_waiting_room,wasted_cpu_io_frac,residual_frac,aborts_per_completion,"
                + "aborts_total,restarts_total,lock_wait_p50,lock_wait_p95,lock_wait_p99,"
                + "resources_per_logical_txn_mean,terminated_by,run_wall_s,k_eff_static,warmup_discarded_s,"
                + "config_hash,processor_count,closure_size";
    }

    public String toCsvRow() {
        return String.format(Locale.US,
                "%s,%d,%.6f,%.6f,%d,%s,%d,%d,%d,%d,%d,%d,%.6f,%d,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%d,%d,%.3f,%.3f,%.3f,%.6f,%s,%.3f,%.6f,%.3f,%s,%d,%.6f",
                configId, seed, f, mu, m, topology, mpl, k, instancePoolSize, propertiesPerModel,
                totalModels, d, throughput, completions, measurementWindowS, fracBlocked,
                fracWaitingRoom, wastedCpuIoFrac, residualFrac, abortsPerCompletion,
                abortsTotal, restartsTotal, lockWaitP50, lockWaitP95, lockWaitP99,
                resourcesPerLogicalTxnMean, terminatedBy, runWallS, kEffStatic, warmupDiscardedS,
                configHash, processorCount, closureSize);
    }

    public static final class Builder {
        private String configId;
        private double f;
        private double mu;
        private int m;
        private String topology;
        private int mpl;
        private int k;
        private int instancePoolSize;
        private int propertiesPerModel;
        private int totalModels;
        private int d;
        private double kEffStatic;
        private String configHash = "";
        private int processorCount;
        private double closureSize;
        private long seed;
        private double throughput;
        private int completions;
        private double measurementWindowS;
        private double fracBlocked;
        private double fracWaitingRoom;
        private double wastedCpuIoFrac;
        private double abortsPerCompletion;
        private int abortsTotal;
        private int restartsTotal;
        private double lockWaitP50;
        private double lockWaitP95;
        private double lockWaitP99;
        private double resourcesPerLogicalTxnMean;
        private String terminatedBy;
        private double runWallS;
        private double warmupDiscardedS;

        public Builder configId(String v) { this.configId = v; return this; }
        public Builder f(double v) { this.f = v; return this; }
        public Builder mu(double v) { this.mu = v; return this; }
        public Builder m(int v) { this.m = v; return this; }
        public Builder topology(String v) { this.topology = v; return this; }
        public Builder mpl(int v) { this.mpl = v; return this; }
        public Builder k(int v) { this.k = v; return this; }
        public Builder instancePoolSize(int v) { this.instancePoolSize = v; return this; }
        public Builder propertiesPerModel(int v) { this.propertiesPerModel = v; return this; }
        public Builder totalModels(int v) { this.totalModels = v; return this; }
        public Builder d(int v) { this.d = v; return this; }
        public Builder kEffStatic(double v) { this.kEffStatic = v; return this; }
        public Builder configHash(String v) { this.configHash = v; return this; }
        public Builder processorCount(int v) { this.processorCount = v; return this; }
        public Builder closureSize(double v) { this.closureSize = v; return this; }
        public Builder seed(long v) { this.seed = v; return this; }
        public Builder throughput(double v) { this.throughput = v; return this; }
        public Builder completions(int v) { this.completions = v; return this; }
        public Builder measurementWindowS(double v) { this.measurementWindowS = v; return this; }
        public Builder fracBlocked(double v) { this.fracBlocked = v; return this; }
        public Builder fracWaitingRoom(double v) { this.fracWaitingRoom = v; return this; }
        public Builder wastedCpuIoFrac(double v) { this.wastedCpuIoFrac = v; return this; }
        public Builder abortsPerCompletion(double v) { this.abortsPerCompletion = v; return this; }
        public Builder abortsTotal(int v) { this.abortsTotal = v; return this; }
        public Builder restartsTotal(int v) { this.restartsTotal = v; return this; }
        public Builder lockWaitP50(double v) { this.lockWaitP50 = v; return this; }
        public Builder lockWaitP95(double v) { this.lockWaitP95 = v; return this; }
        public Builder lockWaitP99(double v) { this.lockWaitP99 = v; return this; }
        public Builder resourcesPerLogicalTxnMean(double v) { this.resourcesPerLogicalTxnMean = v; return this; }
        public Builder terminatedBy(String v) { this.terminatedBy = v; return this; }
        public Builder runWallS(double v) { this.runWallS = v; return this; }
        public Builder warmupDiscardedS(double v) { this.warmupDiscardedS = v; return this; }

        public RawRunRow build() {
            return new RawRunRow(this);
        }
    }
}
