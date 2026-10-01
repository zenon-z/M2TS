package de.tum.tssimulator.experiments;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import de.tum.tssimulator.bookkeeping.CprTopologyMode;
import de.tum.tssimulator.config.ConcurrencyModel;
import de.tum.tssimulator.config.SimulatorParameters;
import de.tum.tssimulator.config.TransactionGenerationMode;
import de.tum.tssimulator.core.Model;
import de.tum.tssimulator.core.ModelFactory;
import de.tum.tssimulator.core.Transaction;
import de.tum.tssimulator.experiments.SimulationRunner.SimulationRunOutcome;
import de.tum.tssimulator.experiments.support.ExperimentOutputPaths;
import de.tum.tssimulator.experiments.support.KEffStaticCalculator;
import de.tum.tssimulator.experiments.support.SeedSweepRunner.SweepStats;
import de.tum.tssimulator.experiments.support.SeedSweepRunner;
import de.tum.tssimulator.experiments.support.TerminatedBy;
import de.tum.tssimulator.locking.policy.DeadlockStrategy;
import de.tum.tssimulator.locking.policy.LockingProtocol;

public class DynamicFootprintExperiment {

    private static final String OUTPUT_FILE = "dynamic_footprint_sim.csv";

    // Exact same mpl sweep as TgsPanelBSimCheck
    private static final int[] MPL_VALUES = {1, 2, 4, 8, 12, 16, 24, 32, 48, 64, 96, 128, 192, 256, 384, 512, 640};
    private static final int SIMULATION_SECONDS = 200;
    // Overridable for the slice sweep (-Dts.dynamicFootprint.slice=1000). The sweep tests whether
    // the C2PL phase-removal deficit is the per-dispatch scheduling quantum: hold service constant,
    // shrink the slice, see whether the deficit tracks it. Default unchanged, so every existing
    // invocation is byte-identical.
    private static final long STEP_TIME_SLICE =
            Long.getLong("ts.dynamicFootprint.slice", 10_000L);

    // The collapse transition (N=2..16) is where the dynamic arm's per-seed spread lives -
    // wide CI bands there turned out to be genuine seed-to-seed variance (checked via
    // PerSeedThroughputCheck: individual seeds at k=25/N=4 ranged 0.79-10.08, not clustered),
    // not noise a normal-approximation CI can usefully summarize. 5 seeds can't resolve that
    // shape; 25 can start to. Above N=64 the bands are already tight (static has essentially
    // none, dynamic settles down) - no reason to pay for extra seeds there.
    private static final Set<Integer> BOOSTED_MPL_VALUES = Set.of(2, 4, 8, 16);
    private static final int BOOSTED_SEED_COUNT = 25;
    private static final int DEFAULT_SEED_COUNT = 5;

    // We only need a single deterministic topology for the dynamic config
    private static final long TOPOLOGY_SEED = 1000003L;

    public static void main(String[] args) throws IOException {
        System.setProperty("ts.metrics.throughputTrace", "true");
        // Each seed/mpl point constructs its own fresh SimulationLogger; the default file sink
        // (logs/simulation.log) logs one line per resource release plus every phase/lifecycle
        // event, which at high mpl runs to ~1GB of disk I/O for a SINGLE seed - pure wasted
        // wall-clock cost this sweep never reads (only the aggregated CSV output below matters).
        // See SimulationLogDestinationFactory's ts.simulator.log.discard javadoc.
        System.setProperty("ts.simulator.log.discard", "true");

        SeedSweepRunner sweepRunner = new SeedSweepRunner();

        int[] kTargets = {10, 25, 50};
        double[] fValues = {0.40, 0.47, 0.50};
        int[] kInitials = {2, 5, 10};

        // Optional: rerun a single k-target (e.g. "-Dts.dynamicFootprint.onlyK=10") - lets a
        // corrupted/stale single output file be regenerated without redoing the other two
        // (each is an independent sweep; no reason to pay for all three every time).
        Integer onlyK = Integer.getInteger("ts.dynamicFootprint.onlyK");

        // Validation-ladder subset (operation-level SS2PL work): parsed ONCE, here, never inside
        // runConfigSweep's per-mpl loop - a value re-read per rebuild is exactly the shape of the
        // copyBuilder(lockingProtocol) bug this experiment already shipped. Absent property means
        // the full grid below applies unchanged; this is opt-in and never a new default.
        LadderSubset ladder = LadderSubset.parse(
                System.getProperty("ts.dynamicFootprint.ladder"), kTargets, fValues, MPL_VALUES);
        if (ladder != null) {
            System.out.println("Ladder subset active: " + ladder.describe());
            onlyK = ladder.k();
        }

        // D-oracle arm (VLDB reviewer response, dynamic-footprint follow-up): "-Dts.dynamicFootprint.arm=oracle"
        // runs ONLY the third arm (full CPR closure precomputed at admission, preclaimed
        // atomically under the same C2PL the static arm uses - see SimulatorParameters.
        // oracleClosurePreclaim), writing to its own dynamic_footprint_sim_k{k}_oracle(_raw).csv
        // files rather than the static+dynamic ones - the static/dynamic data already collected
        // is never touched or re-run. Default "default" preserves the original static+dynamic
        // sweep exactly as before.
        //
        // S2PL arm: "-Dts.dynamicFootprint.arm=s2pl" reruns the SAME static+dynamic comparison as
        // "default", changed in exactly one respect - SimulatorParameters.lockingProtocol=S2PL
        // for every config built below, so every admission (parent AND bookkeeper) acquires its
        // locks one at a time as needed (S2PL/hold-and-wait) instead of the default C2PL atomic
        // preclaim, with CautiousWaitingPolicy (SimulatorParameters.deadlockStrategy, explicit
        // below though it's also the Builder default) preventing deadlock. This is the real,
        // already-merged S2PL/DeadlockStrategy machinery (deadlock-prevention-policy-dev via
        // main) - NOT a from-scratch reimplementation. Writes to its own
        // dynamic_footprint_sim_k{k}_s2pl(_raw).csv files - the default/oracle data is never
        // touched or re-run. See plot_dynamic_footprint_s2pl.py for the companion figure.
        //
        // The protocol is selected through SimulatorParameters.lockingProtocol and nowhere else.
        // A second source of truth for it - a mutable static on the admission controller, say - is
        // how an "S2PL" arm ends up running C2PL while still being labelled S2PL. LockingProtocol
        // is read directly by both DefaultLockingBundle (which picks the TransactionLockCoordinator
        // subclass) and ACIDAdmissionController (tryAdmitWithLockCheck()'s admission branch) - see
        // LockingProtocol's javadoc.
        //
        // S2PL-oracle arm ("-Dts.dynamicFootprint.arm=s2pl-oracle"). This arm is meaningful, and
        // not a contradiction in terms: oracleClosurePreclaim (see
        // TransactionFactory.createParentTransaction()) is PURELY a workload-generation flag - it
        // expands the transaction's resource list to the full CPR closure at CREATION time, so
        // there is one transaction and no bookkeeper cascade to discover it at runtime. It has no
        // bearing on which locking protocol then acquires those resources. So "closure known
        // upfront, still acquired incrementally under S2PL hold-and-wait" is buildable, and it is
        // the arm that isolates the S2PL-vs-C2PL protocol effect while holding discovery timing
        // constant. D-oracle(C2PL) vs Dynamic(S2PL) does NOT isolate it, since that comparison
        // varies discovery timing AND protocol at once.
        String arm = System.getProperty("ts.dynamicFootprint.arm", "default");
        boolean oracleOnly = "oracle".equals(arm);
        boolean s2plOracle = "s2pl-oracle".equals(arm);
        boolean s2pl = "s2pl".equals(arm) || s2plOracle;
        LockingProtocol lockingProtocol = s2pl
                ? LockingProtocol.S2PL
                : LockingProtocol.C2PL;

        for (int i = 0; i < kTargets.length; i++) {
            if (onlyK != null && kTargets[i] != onlyK) {
                continue;
            }
            int k = kTargets[i];
            double f = fValues[i];
            int kInit = kInitials[i];

            String armSuffix = s2plOracle ? "_s2pl_oracle" : oracleOnly ? "_oracle" : s2pl ? "_s2pl" : "";
            // Ladder runs get their own filenames so a reduced-cell CSV can never be mistaken for a
            // full-grid one (they differ only by row count otherwise, which is exactly the
            // "was this filtered afterwards?" ambiguity the ladder must not have).
            String suffix = ladder == null ? armSuffix : armSuffix + "_ladder-" + ladder.describe();
            String outputPath = ExperimentOutputPaths.resultsFile("dynamic_footprint_sim_k" + k + suffix + ".csv");
            String rawOutputPath = ExperimentOutputPaths.resultsFile("dynamic_footprint_sim_k" + k + suffix + "_raw.csv");
            // A THIRD file rather than extra columns on the raw one. The raw CSV is what rung 0 is
            // compared against byte-for-byte; adding columns to it would make every rung differ from
            // the anchor by construction and destroy the one exact check the ladder has. Ladder runs
            // only - a full-grid sweep writes no metrics file.
            String metricsOutputPath = ladder == null ? null
                    : ExperimentOutputPaths.resultsFile("dynamic_footprint_sim_k" + k + suffix + "_ladder_metrics.csv");

            try (PrintWriter writer = new PrintWriter(new FileWriter(outputPath));
                    PrintWriter rawWriter = new PrintWriter(new FileWriter(rawOutputPath));
                    PrintWriter metricsWriter = metricsOutputPath == null ? null
                            : new PrintWriter(new FileWriter(metricsOutputPath))) {
                if (metricsWriter != null) {
                    // Criterion 3 is reported as a CONJUNCTION, so all three of its parts live on
                    // one row: the hold/span ratio, the service demand it is normalised against, and
                    // the cursor-at-abort histogram. Each of the three defects found during
                    // implementation moved the ratio favourably on its own; none of them produced a
                    // favourable ratio together with flat demand and a non-degenerate histogram. A
                    // row that has to satisfy all three at once is the check a single number is not.
                    metricsWriter.println("workload,mpl,seed,rung,operation_batch_size,write_fraction,"
                            + "write_only_cpr_sources,throughput,restarts_total,groups_completed,"
                            + "mean_lock_hold_per_lock_ms,lock_hold_recording_count,total_locks_acquired,"
                            + "mean_txn_span_ms,hold_span_ratio,"
                            + "executed_ops_total,executed_ops_per_completion,demand_ms_per_completion,"
                            + "cursor_b0,cursor_b1,cursor_b2,cursor_b3,cursor_b4,"
                            + "cursor_b5,cursor_b6,cursor_b7,cursor_b8,cursor_b9,cursor_samples,"
                            + "upgrades_granted,upgrades_denied,soft_deny_release_cycles,"
                            // Span decomposition. Interleaving lengthens a transaction's span, and
                            // "why" has two very different answers: time BLOCKED on a lock another
                            // transaction holds (real hold-and-wait contention, the effect being
                            // measured) versus time QUEUEING for a LOCK-phase server slot. The
                            // latter is an artifact of modelling LOCK as a served phase - a real
                            // system does not queue for a lock-manager call the way it queues for
                            // IO - and re-entering that queue k times instead of once is a cost
                            // interleaving pays here but would not pay in practice. Reported so a
                            // throughput drop can be attributed rather than assumed.
                            + "total_lock_service_ms,lock_service_ms_per_completion,"
                            + "lock_wait_mean_ms,lock_wait_p50_ms,lock_wait_p95_ms,lock_wait_p99_ms,"
                            + "lock_wait_samples,residual_ms_per_completion,"
                            + "mean_acquisition_span_ms,acquisition_span_count,hold_acqspan_ratio,"
                            + "terminated_by,ladder_subset,step_time_slice,group_unit_affinity,locking_protocol,transaction_mode,"
                            + "backoff_interval_count,backoff_interval_total");
                }
                writer.println("workload,D,k_initial,mpl,throughput_mean,throughput_ci,"
                        + "mean_locks_held_count_sampled_mean,peak_locks_held_count_mean,n_mean,"
                        + "mean_running_state_count_sampled_mean,peak_running_state_count_mean,"
                        + "mean_admission_waiter_count_sampled_mean,peak_admission_waiter_count_mean,"
                        + "blocked_at_check_rate_mean,total_admission_attempts_mean,total_blocked_at_check_mean,"
                        + "restarts_total_mean,seed_count,success_count,watchdog_count,status,"
                        + "mean_locks_held_count_sampled_stddev,restarts_total_stddev,mean_admission_waiter_count_sampled_stddev,"
                        + "mean_pending_state_count_sampled_mean,mean_blocked_state_count_sampled_mean,mean_ready_state_count_sampled_mean,"
                        + "step_time_slice,group_unit_affinity,locking_protocol,transaction_mode");
                // Raw, one row per (workload, mpl, seed) - never pre-aggregated. This is what the
                // dot-plot / log-space summary in the figure reads; the aggregated file above is
                // kept for the other figures (state decomposition, failure modes) that still key
                // off mean/CI columns.
                // ladder_subset is provenance, not config: it records WHICH cell of the grid this
                // file covers, so a rung CSV is self-identifying rather than being a full-grid file
                // someone may or may not have filtered. Deliberately NOT threaded into
                // SimulatorParameters - it does not affect any individual run's behaviour (a given
                // (k,f,mpl,seed) point is bit-identical whether it came from the ladder or the full
                // sweep), so putting it there would make EffectiveConfigHasher report two
                // behaviourally-identical configs as different, breaking exactly the rung-0-against-
                // reference comparison the ladder exists to make. Trailing column: both project
                // scripts read by name via pd.read_csv, so appending is safe.
                // locking_protocol names how ONE TRANSACTION takes its own footprint, NOT the
                // cell's discipline. On any cascading cell (every "dynamic"/oracle row) the group
                // acquires in waves - the parent holds its locks in the waiting room while its
                // bookkeepers, which are only created when it completes, acquire theirs - so the
                // group is doing hold-and-wait whatever this column says. Read it with the workload
                // column, and see LockingProtocol's javadoc.
                //
                // step_time_slice and group_unit_affinity are the two settings that silently set
                // this experiment's results before they were echoed: the scheduling quantum moved a
                // C2PL deficit from 10.1% to 0.97% across a 10x change, and group unit affinity is
                // worth ~41-70% of dynamic-arm throughput. Both are now on every row, so a reader
                // can tell which model produced the number instead of having to know the defaults.
                rawWriter.println("workload,mpl,seed,throughput,restarts_total,blocked_at_check_rate,"
                        + "terminated_by,ladder_subset,step_time_slice,group_unit_affinity,locking_protocol,transaction_mode");

                if (s2plOracle) {
                    // Same configOracle() as the C2PL D-oracle arm (identical closure-preclaim
                    // workload) - only lockingProtocol differs, applied below. Isolates the
                    // S2PL-vs-C2PL protocol effect alone, holding discovery-timing constant (see
                    // the arm javadoc above).
                    System.out.println("Running S2PL-oracle Workload (k=" + k + ", f=" + f + ")...");
                    runConfigSweep(writer, rawWriter, metricsWriter, sweepRunner, "s2pl-oracle",
                            SeedSweepRunner.copyBuilder(configOracle(f, kInit)).lockingProtocol(lockingProtocol).build(),
                            MPL_VALUES, k, ladder);
                } else if (oracleOnly) {
                    // Run D-oracle Config
                    System.out.println("Running D-oracle Workload (k=" + k + ", f=" + f + ")...");
                    runConfigSweep(writer, rawWriter, metricsWriter, sweepRunner, "oracle", configOracle(f, kInit), MPL_VALUES, k, ladder);
                } else {
                    // Run Static Config (Control)
                    System.out.println("Running Static Control Workload (k=" + k + (s2pl ? ", S2PL" : "") + ")...");
                    runConfigSweep(writer, rawWriter, metricsWriter, sweepRunner, "static",
                            SeedSweepRunner.copyBuilder(configStatic(k)).lockingProtocol(lockingProtocol).build(),
                            MPL_VALUES, k, ladder);

                    // Run Dynamic Config
                    System.out.println("Running Dynamic Workload (k=" + k + ", f=" + f + (s2pl ? ", S2PL" : "") + ")...");
                    runConfigSweep(writer, rawWriter, metricsWriter, sweepRunner, "dynamic",
                            SeedSweepRunner.copyBuilder(configDynamic(f, kInit)).lockingProtocol(lockingProtocol).build(),
                            MPL_VALUES, k, ladder);
                }
            }
            System.out.println("Results exported to: " + outputPath + " and " + rawOutputPath);
        }
    }

    /** Package-private, not private: {@link S2PLStaticWorkPerCompletionCheck} runs a diagnostic
     * against this exact config and must not carry its own copy, which would drift. */
    static SimulatorParameters configStatic(int kStatic) {
        return SimulatorParameters.builder()
                .transactionGenerationMode(TransactionGenerationMode.RANDOM_OPERATIONS)
                .useRandomResourceSelection(true)
                .totalModels(1)
                .propertiesPerModel(List.of(1))
                .instancePoolSize(5000)
                .totalLocks(5000)
                .maxTransactionModels(1)
                .useConstantTransactionSize(true)
                .constantTransactionSize(kStatic)
                .semanticOverlap(0.0)
                .numberOfIoProcessors(700)
                .numberOfCpuProcessors(700)
                .concurrencyModel(ConcurrencyModel.ACID)
                // Canonical cheap cost on all four fields, set explicitly rather than left to the
                // Builder default even though the two currently agree. A class that overrides only
                // the lock-specific pair inherits whatever the resource-cost default happens to be,
                // and an expensive one (300ms/resource) manufactures a "dynamic wins at low load"
                // result: the dynamic parent's 5x-smaller kInitial footprint buys a 5x shorter
                // critical path while its cascade costs nothing on that timeline. Pinning all four
                // here means a future default change cannot reintroduce that.
                .ioTimePerResource(SimulatorParameters.CANONICAL_IO_TIME_PER_RESOURCE)
                .cpuTimePerResource(SimulatorParameters.CANONICAL_CPU_TIME_PER_RESOURCE)
                .lockIOTimePerResource(SimulatorParameters.CANONICAL_LOCK_IO_TIME_PER_RESOURCE)
                .lockCPUTimePerResource(SimulatorParameters.CANONICAL_LOCK_CPU_TIME_PER_RESOURCE)
                // Explicit, not the Builder's default of 1 (CapacityWaitManager.wakeNext() drains
                // exactly this many waiters per completion/abort) - matches every sibling
                // experiment on this same contended-CPR shape (AbGroupLifetimeExperiment,
                // FootprintABExperiment, CprMplExperimentDefaults, StabilityDiagnostic all set 4).
                // This class silently rode the default instead; traced CAPACITY_LIMIT registration
                // (the only trigger for this queue) as rare at these settings, so not expected to
                // move the numbers much, but leaving two comparable configs on different batch
                // sizes is its own bug independent of whether it explains anything already seen.
                .admissionBatchSize(4)
                .stepTimeSlice(STEP_TIME_SLICE)
                .simulationDurationSeconds(SIMULATION_SECONDS)
                // Explicit, not the Builder's default (even though it matches) - see the S2PL arm
                // comment in main() above. Inert for the default/oracle arms (ENABLE_C2PL=true
                // bypasses the WaitPolicyStrategy entirely via the atomic preclaim check), real
                // for the s2pl arm.
                .deadlockStrategy(DeadlockStrategy.CAUTIOUS_WAITING)
                .build();
    }

    private static SimulatorParameters configDynamic(double semanticOverlap, int kInitial) {
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
                .constantTransactionSize(kInitial) // Passed as parameter

                // To get E[C] = 20 CUs total (4 per initial lock)
                // R = f * mu = 0.8
                // For a finite graph (5000 nodes), overlap can be tweaked if it falls short.
                .semanticOverlap(semanticOverlap)
                .avgCprsPerResource(2.0)
                .maxCprsPerResource(6)
                .cprTopologyMode(CprTopologyMode.UNIFORM)
                .semanticOverlapSeed(TOPOLOGY_SEED)

                .numberOfIoProcessors(700)
                .numberOfCpuProcessors(700)
                .concurrencyModel(ConcurrencyModel.ACID)
                // Canonical cheap cost on all four fields, set explicitly rather than left to the
                // Builder default even though the two currently agree. A class that overrides only
                // the lock-specific pair inherits whatever the resource-cost default happens to be,
                // and an expensive one (300ms/resource) manufactures a "dynamic wins at low load"
                // result: the dynamic parent's 5x-smaller kInitial footprint buys a 5x shorter
                // critical path while its cascade costs nothing on that timeline. Pinning all four
                // here means a future default change cannot reintroduce that.
                .ioTimePerResource(SimulatorParameters.CANONICAL_IO_TIME_PER_RESOURCE)
                .cpuTimePerResource(SimulatorParameters.CANONICAL_CPU_TIME_PER_RESOURCE)
                .lockIOTimePerResource(SimulatorParameters.CANONICAL_LOCK_IO_TIME_PER_RESOURCE)
                .lockCPUTimePerResource(SimulatorParameters.CANONICAL_LOCK_CPU_TIME_PER_RESOURCE)
                // Explicit, not the Builder's default of 1 (CapacityWaitManager.wakeNext() drains
                // exactly this many waiters per completion/abort) - matches every sibling
                // experiment on this same contended-CPR shape (AbGroupLifetimeExperiment,
                // FootprintABExperiment, CprMplExperimentDefaults, StabilityDiagnostic all set 4).
                // This class silently rode the default instead; traced CAPACITY_LIMIT registration
                // (the only trigger for this queue) as rare at these settings, so not expected to
                // move the numbers much, but leaving two comparable configs on different batch
                // sizes is its own bug independent of whether it explains anything already seen.
                .admissionBatchSize(4)
                .stepTimeSlice(STEP_TIME_SLICE)
                .simulationDurationSeconds(SIMULATION_SECONDS)
                .deadlockStrategy(DeadlockStrategy.CAUTIOUS_WAITING)
                .build();
    }

    /**
     * D-oracle arm: byte-for-byte configDynamic() (same model pool, same schema parameters, same
     * TOPOLOGY_SEED - so the exact same CPR closure every transaction would eventually reach via
     * the dynamic arm's cascade), plus oracleClosurePreclaim(true) - the ONLY difference from
     * configDynamic() is WHEN/HOW that closure is committed: precomputed and preclaimed
     * atomically at admission (SimulatorParameters.oracleClosurePreclaim, see TransactionFactory.
     * expandToOracleClosure()) instead of discovered hop-by-hop via SchemaCprFactory's runtime
     * bookkeeper cascade. Isolates the acquisition PROTOCOL from the footprint SIZE: S (static)
     * vs D-oracle isolates work amplification (both fixed/atomic, different size); D-oracle vs D
     * (dynamic) isolates dynamism alone (both reach the same closure, different protocol).
     */
    /** Package-private for the same reason as {@link #configStatic}: {@link OracleDeterminismProbe}
     * must probe this exact config, not a copy of it. */
    static SimulatorParameters configOracle(double semanticOverlap, int kInitial) {
        return SeedSweepRunner.copyBuilder(configDynamic(semanticOverlap, kInitial))
                .oracleClosurePreclaim(true)
                .build();
    }

    /**
     * A reduced (k, f, mpl-set, seed-count) cell of the grid above, for the operation-level SS2PL
     * validation ladder. Spec format {@code k:f:mpl,mpl,...:seeds}, e.g.
     * {@code -Dts.dynamicFootprint.ladder=25:0.47:4,16,64,256:5}.
     *
     * <p>The ladder compares four rungs against a rung-0 reference and needs every rung to be the
     * same cell, produced by the same code path. Hence a single self-documenting spec string rather
     * than a combination of the pre-existing {@code onlyK}/{@code uniformSeeds} properties: the
     * invocation that produced a CSV can be read off one value and reproduced exactly.
     *
     * <p>Every component is validated against the grid it subsets. An off-grid k or mpl aborts with
     * the valid set rather than running something the baseline cannot be compared against - a
     * silently-accepted k=30 would produce a plausible-looking CSV that is not a cell of this
     * experiment at all. f must match the value the grid pairs with that k, so the spec cannot
     * assert a (k, f) combination the experiment never runs.
     */
    /**
     * The four ladder rungs, each carrying the settings that DEFINE it.
     *
     * <p><b>The label must not be a filename tag only.</b> If the settings it names -
     * {@code operationBatchSize}, {@code writeFraction}, {@code writeOnlyCprSources} - are not
     * applied, running "rung1" re-runs rung 0 under a different filename, and that failure is
     * invisible in the worst possible way: criterion 2 asserts C2PL is IDENTICAL between rungs 0
     * and 1, so an inert rung 1 satisfies it perfectly, for both protocols, and the conclusion
     * drawn is "the cursor is correctly gated behind LockingProtocol" from a run in which nothing
     * was gated because nothing was switched on. Same signature as the other three
     * defects on this work: the broken version produces the result the hypothesis wants.
     *
     * <p>Binding the settings to the label removes the gap by construction. There is no way to name
     * a rung without getting its parameters, and no way to change its parameters without changing
     * what every rung file of that name means.
     */
    enum Rung {
        /** Whole-footprint acquisition, all-WRITE. The anchor: every setting at its default, so the
         * output must be bit-identical to the pre-interleaving baseline. */
        RUNG0("rung0", 0, 1.0, true),
        /** Interleaving on, one operation at a time, still all-WRITE. Isolates acquisition timing:
         * the ONLY difference from rung 0 is when locks are taken. */
        RUNG1("rung1", 1, 1.0, true),
        /** Mixed read/write, CPR fan-out held at full width so the S/X effect is not confounded with
         * a narrower cascade. */
        RUNG2("rung2", 1, 0.5, false),
        /** Write-only CPR sourcing added on top of rung 2 - the one variable between them. */
        RUNG3("rung3", 1, 0.5, true);

        private final String label;
        private final int operationBatchSize;
        private final double writeFraction;
        private final boolean writeOnlyCprSources;

        Rung(String label, int operationBatchSize, double writeFraction, boolean writeOnlyCprSources) {
            this.label = label;
            this.operationBatchSize = operationBatchSize;
            this.writeFraction = writeFraction;
            this.writeOnlyCprSources = writeOnlyCprSources;
        }

        static Rung forLabel(String label) {
            for (Rung rung : values()) {
                if (rung.label.equalsIgnoreCase(label)) {
                    return rung;
                }
            }
            throw new IllegalArgumentException("ts.dynamicFootprint.ladder rung '" + label
                    + "' is not a defined rung. Valid rungs are "
                    + Arrays.stream(values()).map(r -> r.label).toList()
                    + ". An arbitrary label is rejected rather than accepted as a bare filename tag:"
                    + " a rung whose settings are not applied still produces a plausible CSV, and an"
                    + " inert rung 1 satisfies the C2PL-unchanged criterion vacuously.");
        }

        List<String> settingsDescription() {
            return List.of("operationBatchSize=" + operationBatchSize,
                    "writeFraction=" + writeFraction,
                    "writeOnlyCprSources=" + writeOnlyCprSources);
        }

        SimulatorParameters apply(SimulatorParameters base) {
            return SeedSweepRunner.copyBuilder(base)
                    .operationBatchSize(operationBatchSize)
                    .writeFraction(writeFraction)
                    .writeOnlyCprSources(writeOnlyCprSources)
                    .build();
        }
    }

    private record LadderSubset(int k, double f, int[] mplValues, int seedCount, String rung) {

        /** The settings this rung's label names. */
        Rung settings() {
            return Rung.forLabel(rung);
        }

        static LadderSubset parse(String spec, int[] kTargets, double[] fValues, int[] gridMplValues) {
            if (spec == null || spec.isBlank()) {
                return null; // absent = full grid, unchanged
            }
            String[] parts = spec.split(":");
            if (parts.length != 5) {
                throw new IllegalArgumentException("ts.dynamicFootprint.ladder must be "
                        + "'k:f:mpl,mpl,...:seeds:rung' (5 colon-separated parts), got: " + spec);
            }

            int k = parseInt(parts[0], "k", spec);
            int kIndex = indexOf(kTargets, k);
            if (kIndex < 0) {
                throw new IllegalArgumentException("ts.dynamicFootprint.ladder k=" + k
                        + " is not one of this experiment's k-targets " + Arrays.toString(kTargets)
                        + " - a k outside the grid produces a cell no baseline can be compared against");
            }

            double f = parseDouble(parts[1], "f", spec);
            if (Double.compare(f, fValues[kIndex]) != 0) {
                throw new IllegalArgumentException("ts.dynamicFootprint.ladder f=" + f
                        + " does not match the f this grid pairs with k=" + k + " (" + fValues[kIndex]
                        + "). The grid pairs k/f by index " + Arrays.toString(kTargets) + " / "
                        + Arrays.toString(fValues) + "; the spec cannot assert a combination the"
                        + " experiment never runs");
            }

            String[] mplTokens = parts[2].split(",");
            int[] mplValues = new int[mplTokens.length];
            for (int i = 0; i < mplTokens.length; i++) {
                mplValues[i] = parseInt(mplTokens[i], "mpl", spec);
                if (indexOf(gridMplValues, mplValues[i]) < 0) {
                    throw new IllegalArgumentException("ts.dynamicFootprint.ladder mpl=" + mplValues[i]
                            + " is not one of MPL_VALUES " + Arrays.toString(gridMplValues));
                }
            }

            int seedCount = parseInt(parts[3], "seeds", spec);
            if (seedCount < 1) {
                throw new IllegalArgumentException("ts.dynamicFootprint.ladder seeds=" + seedCount
                        + " must be >= 1");
            }

            // Required, not optional. Every ladder rung runs the SAME cell by construction, so
            // without a rung label each rung's output would land on the previous rung's filename
            // and silently destroy the rung-0 reference it is supposed to be compared against.
            // artifacts/ is gitignored, so an overwritten baseline is not recoverable from git.
            // Required rather than defaulted because a forgotten label is precisely the case that
            // loses data.
            String rung = parts[4].trim();
            if (rung.isEmpty()) {
                throw new IllegalArgumentException("ts.dynamicFootprint.ladder rung label is empty"
                        + " (in spec '" + spec + "') - it is required so a later rung cannot"
                        + " overwrite an earlier rung's output for the same cell");
            }
            if (!rung.matches("[A-Za-z0-9._-]+")) {
                throw new IllegalArgumentException("ts.dynamicFootprint.ladder rung label '" + rung
                        + "' must be filename-safe ([A-Za-z0-9._-]+) - it goes into the output filename");
            }
            // Resolve here, at parse time, so an undefined rung fails before any run starts rather
            // than after producing a directory of plausible CSVs.
            Rung.forLabel(rung);
            return new LadderSubset(k, f, mplValues, seedCount, rung);
        }

        private static int indexOf(int[] haystack, int needle) {
            for (int i = 0; i < haystack.length; i++) {
                if (haystack[i] == needle) {
                    return i;
                }
            }
            return -1;
        }

        private static int parseInt(String token, String field, String spec) {
            try {
                return Integer.parseInt(token.trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("ts.dynamicFootprint.ladder " + field
                        + " is not an integer: '" + token + "' (in spec '" + spec + "')", e);
            }
        }

        private static double parseDouble(String token, String field, String spec) {
            try {
                return Double.parseDouble(token.trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("ts.dynamicFootprint.ladder " + field
                        + " is not a number: '" + token + "' (in spec '" + spec + "')", e);
            }
        }

        /** Provenance string - goes into the output filename AND every raw row (see below). */
        String describe() {
            return String.format(Locale.US, "%s-k%d-f%.2f-mpl%s-s%d", rung, k, f,
                    Arrays.stream(mplValues).mapToObj(Integer::toString)
                            .collect(Collectors.joining(".")),
                    seedCount);
        }
    }

    /**
     * One row carrying criterion 3's three parts together: the hold/span ratio, the service demand
     * it is normalised against, and the cursor-at-abort histogram.
     *
     * <p>They are emitted as a unit because each of the three defects found while building this
     * moved the ratio in the favourable direction on its own - a favourable ratio is the shape all
     * three took. None of them produced a favourable ratio AND flat demand AND a non-degenerate
     * histogram. Reading the ratio alone would have been satisfied by every one of them.
     *
     * <p>The ratio is normalised against rung 1's OWN span, not rung 0's. A transaction under
     * interleaving re-enters the LOCK queue k times and accrues queueing delay on each re-entry, so
     * its span grows; dividing by rung 0's span would confound that growth with the interleaving
     * effect. Within-run: correct accounting gives ~0.5, batch-duration accounting ~1/k.
     */
    private static void writeLadderMetricsRow(PrintWriter metricsWriter, String workloadName, int mpl,
            long seed, LadderSubset ladder, SimulatorParameters params, SimulationRunOutcome outcome,
            String ladderTag) {
        var snapshot = outcome.snapshot();
        long completed = snapshot.groupsCompletedTotal();
        double meanHoldPerLock = snapshot.meanLockHoldDurationPerLock();
        double meanSpan = snapshot.parentTransactionLatencyStats() == null ? 0.0
                : snapshot.parentTransactionLatencyStats().mean;
        double holdSpanRatio = meanSpan <= 0.0 ? Double.NaN : meanHoldPerLock / meanSpan;
        double execOpsPerCompletion = completed == 0 ? Double.NaN
                : (double) snapshot.executedOperationsTotal() / completed;
        double demandPerCompletion = completed == 0 ? Double.NaN
                : (double) (snapshot.completedIoTime() + snapshot.completedCpuTime()) / completed;

        long[] buckets = snapshot.cursorProgressAtAbortBuckets();
        long cursorSamples = 0;
        StringBuilder bucketCells = new StringBuilder();
        for (int b = 0; b < 10; b++) {
            long value = buckets != null && b < buckets.length ? buckets[b] : 0L;
            cursorSamples += value;
            bucketCells.append(value).append(',');
        }

        // Span decomposition: what a transaction's span is actually made of. Service demand and
        // LOCK-phase service are both flat across batch sizes by construction, so any span growth
        // sits in lock WAIT (blocked on a conflicting holder - the real effect) or in the residual
        // (queueing for a server slot, which for the LOCK phase is a modelling artifact).
        // Criterion 3's ratio against the denominator the derivation actually specifies: the
        // acquisition-to-release span, not submission-to-completion latency. Correct accounting
        // gives ~0.5 and should be FLAT in N, since the static cell has single-member groups and no
        // cascade; batch-duration accounting would give ~1/k.
        double holdAcqSpanRatio = snapshot.meanAcquisitionSpan() <= 0.0 ? Double.NaN
                : meanHoldPerLock / snapshot.meanAcquisitionSpan();

        var lockWait = snapshot.lockWaitStats();
        double lockServicePerCompletion = completed == 0 ? Double.NaN
                : (double) snapshot.totalLockTime() / completed;
        double residualPerCompletion = completed == 0 ? Double.NaN
                : meanSpan - demandPerCompletion - lockServicePerCompletion
                        - (lockWait == null ? 0.0 : lockWait.mean);

        metricsWriter.printf(Locale.US,
                "%s,%d,%d,%s,%d,%.4f,%b,%.6f,%d,%d,%.6f,%d,%.4f,%.6f,%.6f,%d,%.6f,%.6f,%s%d,%d,%d,%d,"
                        + "%d,%.6f,%.6f,%.6f,%.6f,%.6f,%d,%.6f,%.6f,%d,%.6f,%s,%s,%d,%b,%s,%s,%d,%d%n",
                workloadName, mpl, seed, ladder.rung(), params.operationBatchSize, params.writeFraction,
                params.writeOnlyCprSources, outcome.result().throughput, snapshot.restartsTotal(),
                completed, meanHoldPerLock, snapshot.lockHoldRecordingCount(),
                snapshot.totalLocksAcquiredCount(), meanSpan, holdSpanRatio,
                snapshot.executedOperationsTotal(), execOpsPerCompletion, demandPerCompletion,
                bucketCells, cursorSamples, snapshot.upgradesGranted(), snapshot.upgradesDenied(),
                snapshot.softDenyReleaseCyclesTotal(),
                snapshot.totalLockTime(), lockServicePerCompletion,
                lockWait == null ? 0.0 : lockWait.mean, lockWait == null ? 0.0 : lockWait.p50,
                lockWait == null ? 0.0 : lockWait.p95, lockWait == null ? 0.0 : lockWait.p99,
                lockWait == null ? 0L : (long) lockWait.sampleCount, residualPerCompletion,
                snapshot.meanAcquisitionSpan(), snapshot.acquisitionSpanCount(), holdAcqSpanRatio,
                outcome.terminatedBy(), ladderTag,
                params.stepTimeSlice, params.groupUnitAffinity, params.lockingProtocol,
                params.transactionMode,
                snapshot.backoffIntervalCount(), snapshot.backoffIntervalTotal());
    }

    private static void runConfigSweep(PrintWriter writer, PrintWriter rawWriter, PrintWriter metricsWriter, SeedSweepRunner sweepRunner,
            String workloadName, SimulatorParameters baseParams, int[] mplValues, int targetK,
            LadderSubset ladder) throws IOException {

        // Ladder subset narrows the sweep to its own mpl set. Taken from the already-parsed record
        // rather than re-read here - see the parse call site in main().
        if (ladder != null) {
            mplValues = ladder.mplValues();
        }
        // "full-grid" rather than an empty cell: the column exists on every row so the schema is
        // stable for downstream readers, and a full-sweep row says so explicitly instead of leaving
        // a blank that could equally mean "ladder run, tag lost".
        String ladderTag = ladder == null ? "full-grid" : ladder.describe();

        // Print the analytical k_eff for verification
        if ("dynamic".equals(workloadName)) {
            List<Model> modelPool = new ModelFactory(baseParams.instancePoolSize).createModels(baseParams.propertiesPerModel);
            double kEffStatic = KEffStaticCalculator.compute(modelPool, baseParams.cprTopologyMode, baseParams.semanticOverlap,
                    baseParams.avgCprsPerResource, baseParams.maxCprsPerResource, TOPOLOGY_SEED, baseParams.constantTransactionSize);
            System.out.printf(Locale.US, "Analytical k_eff for dynamic = %.2f%n", kEffStatic);
        }

        // The rung's settings are what MAKE it that rung - applied here, to the base config, before
        // the per-mpl copy. At rung 0 every value equals the Builder default, so this is a literal
        // no-op and rung-0 output stays bit-identical to the pre-interleaving baseline; that is
        // asserted, not assumed, by re-diffing the rung-0 cells after this change.
        if (ladder != null) {
            baseParams = ladder.settings().apply(baseParams);
        }

        for (int mpl : mplValues) {
            SimulatorParameters params = SeedSweepRunner.copyBuilder(baseParams)
                    .initialTransactions(mpl)
                    .build();

            // EVERY mpl POINT GETS THE SAME SEED COUNT. Boosting seeds at a few points (5x at 4
            // of 17) was a response to wide CI bands caused by a reentrant-abort dropped-reference
            // bug, which is fixed; an uneven seed allocation now only makes the points
            // incomparable. -Dts.dynamicFootprint.boostedSeeds=true opts back into the uneven
            // allocation, and -Dts.dynamicFootprint.uniformSeeds=N overrides the count outright.
            Integer uniformSeeds = Integer.getInteger("ts.dynamicFootprint.uniformSeeds");
            boolean useBoostedSeeds = Boolean.getBoolean("ts.dynamicFootprint.boostedSeeds");
            // Ladder wins over the boosted/default logic (its whole point is one fixed seed count
            // across every mpl, so rungs are comparable), but NOT over an explicit uniformSeeds -
            // that stays the outright override it already documents itself as.
            int seedCount = uniformSeeds != null ? uniformSeeds
                    : ladder != null ? ladder.seedCount()
                    : (useBoostedSeeds && BOOSTED_MPL_VALUES.contains(mpl)) ? BOOSTED_SEED_COUNT
                    : DEFAULT_SEED_COUNT;
            long[] seeds = SeedSweepRunner.defaultSeeds(seedCount);

            String runName = String.format(Locale.US, "dynamicFootprint_%s_mpl%d", workloadName, mpl);
            System.out.printf(Locale.US, "Running %s (mpl=%d, seeds=%d)%n", runName, mpl, seedCount);

            // Censoring, not runRaw: a watchdog-hung seed still produces a row here (with a
            // best-effort partial snapshot) instead of silently vanishing, so the raw file below
            // can show it as a labeled point rather than a gap.
            var allOutcomes = sweepRunner.runRawWithCensoring(runName, params, seeds);

            for (int i = 0; i < allOutcomes.size(); i++) {
                SimulationRunOutcome outcome = allOutcomes.get(i);
                long seed = i < seeds.length ? seeds[i] : -1;
                long total = outcome.snapshot().totalAdmissionAttemptsCount();
                long blocked = outcome.snapshot().totalBlockedAtCheckCount();
                double blockedRate = total <= 0 ? 0.0 : (double) blocked / total;
                rawWriter.printf(Locale.US, "%s,%d,%d,%.6f,%d,%.6f,%s,%s,%d,%b,%s,%s%n",
                        workloadName, mpl, seed, outcome.result().throughput,
                        outcome.snapshot().restartsTotal(), blockedRate, outcome.terminatedBy(),
                        ladderTag, params.stepTimeSlice, params.groupUnitAffinity, params.lockingProtocol,
                        params.transactionMode);

                if (metricsWriter != null) {
                    writeLadderMetricsRow(metricsWriter, workloadName, mpl, seed, ladder,
                            params, outcome, ladderTag);
                }
            }
            rawWriter.flush();
            if (metricsWriter != null) {
                metricsWriter.flush();
            }

            // Aggregated stats exclude watchdog-censored seeds: summarizeMetric's
            // watchdogCount = seedCount - outcomes.size() assumes the list handed to it has
            // censored runs removed already.
            var outcomes = allOutcomes.stream()
                    .filter(o -> o.terminatedBy() != TerminatedBy.WATCHDOG)
                    .toList();

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

            SweepStats pendingStateStats = SeedSweepRunner.summarizeMetric(outcomes, seeds.length,
                    outcome -> outcome.snapshot().meanPendingStateCountSampled());
            SweepStats blockedStateStats = SeedSweepRunner.summarizeMetric(outcomes, seeds.length,
                    outcome -> outcome.snapshot().meanBlockedStateCountSampled());
            SweepStats readyStateStats = SeedSweepRunner.summarizeMetric(outcomes, seeds.length,
                    outcome -> outcome.snapshot().meanReadyStateCountSampled());

            String status = throughputStats.allWatchdog() ? "WATCHDOG"
                    : throughputStats.watchdogCount() > 0 ? "PARTIAL" : "OK";

            // Expected n calculation normalizes by targetK
            double nMean = meanLocksHeldStats.mean() / targetK;

            writer.printf(Locale.US,
                    "%s,%d,%d,%d,%.6f,%.6f,%.3f,%.3f,%.4f,%.3f,%.3f,%.3f,%.3f,%.6f,%.1f,%.1f,%.3f,%d,%d,%d,%s,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%d,%b,%s,%s%n",
                    workloadName, 5000, baseParams.constantTransactionSize, mpl,
                    throughputStats.mean(), throughputStats.ci95(),
                    meanLocksHeldStats.mean(), peakLocksHeldStats.mean(), nMean,
                    meanRunningStats.mean(), peakRunningStats.mean(),
                    meanWaiterStats.mean(), peakWaiterStats.mean(),
                    blockedRateStats.mean(), totalAttemptsStats.mean(), totalBlockedStats.mean(),
                    restartsStats.mean(),
                    throughputStats.seedCount(), throughputStats.successCount(),
                    throughputStats.watchdogCount(), status,
                    meanLocksHeldStats.stddev(), restartsStats.stddev(), meanWaiterStats.stddev(),
                    pendingStateStats.mean(), blockedStateStats.mean(), readyStateStats.mean(),
                    baseParams.stepTimeSlice, baseParams.groupUnitAffinity, baseParams.lockingProtocol,
                    baseParams.transactionMode);
            writer.flush();

            Transaction.resetMetrics();
        }
    }
}
