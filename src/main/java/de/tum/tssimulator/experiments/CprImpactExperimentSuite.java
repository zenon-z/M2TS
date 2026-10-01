package de.tum.tssimulator.experiments;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.List;
import java.util.Locale;

import de.tum.tssimulator.bookkeeping.CprGraphMetrics;
import de.tum.tssimulator.bookkeeping.ReferentialAction;
import de.tum.tssimulator.bootstrap.SimulationWatchdogException;
import de.tum.tssimulator.config.ConcurrencyModel;
import de.tum.tssimulator.config.SimulatorParameters;
import de.tum.tssimulator.config.TransactionMode;
import de.tum.tssimulator.core.Model;
import de.tum.tssimulator.core.ModelFactory;
import de.tum.tssimulator.core.Transaction;
import de.tum.tssimulator.cpr.CprSchema;
import de.tum.tssimulator.cpr.CprSchemaBuilder;
import de.tum.tssimulator.experiments.support.ExperimentOutputPaths;
import de.tum.tssimulator.experiments.support.SeedSweepRunner;

/**
 * ACID-only CPR impact suite for throughput, cascade growth, state pressure,
 * lock contention, and latency views.
 */
public class CprImpactExperimentSuite {
    private static final String OUTPUT_FILE = "cpr_impact_experiment_suite.csv";
    private static final String NO_MPL_OUTPUT_FILE = "cpr_impact_experiment_suite_no_mpl.csv";
    private static final String LONG_LATENCY_OUTPUT_FILE = "cpr_impact_latency_long.csv";
    private static final String LONG_LATENCY_ONLY_PROPERTY = "ts.cprImpact.longLatencyOnly";
    private static final String DISABLE_MPL_LIMIT_PROPERTY = "ts.simulator.disableMplLimit";

    private static final int[] MODEL_COUNTS = {5, 10, 30};
    private static final int[] PROCESSORS = {1, 2, 4, 8, 15, 30};
    private static final double[] SEMANTIC_OVERLAPS = {0.00, 0.05, 0.10, 0.20, 0.30, 0.50};
    private static final double[] AVG_FANOUTS = {1.0, 2.0, 4.0, 6.0, 8.0};
    private static final int[] MAX_FANOUTS = {2, 3, 5, 8, 10};
    private static final int[] WORKLOAD_INTENSITIES = {10, 25, 50, 100, 200};
    private static final int[] TERMINAL_COUNTS = {10, 25, 50, 100, 200};
    private static final double[] MICRO_OVERLAPS = {0.10, 0.30, 0.50};

    private static final double FIXED_OVERLAP = 0.30;
    private static final int FIXED_MAX_FANOUT = 10;
    private static final double FIXED_AVG_FANOUT = 4.0;
    private static final int FIXED_PROCESSORS = 15;
    private static final int SIMULATION_SECONDS = Integer.getInteger("ts.cprImpact.simulationSeconds", 10000);
    private static final int WATCHDOG_MAX_STEPS_WITHOUT_PROGRESS = 100000;
    private static final String WATCHDOG_STEPS_PROPERTY = "ts.simulator.watchdog.maxStepsWithoutProgress";

    public static void main(String[] args) throws IOException {
        System.setProperty(WATCHDOG_STEPS_PROPERTY, Integer.toString(WATCHDOG_MAX_STEPS_WITHOUT_PROGRESS));
        boolean longLatencyOnly = Boolean.getBoolean(LONG_LATENCY_ONLY_PROPERTY);
        boolean noMplLimit = Boolean.getBoolean(DISABLE_MPL_LIMIT_PROPERTY);
        SimulatorParameters base = baseParameters();
        SimulationRunner runner = new SimulationRunner();
        String outputFile = ExperimentOutputPaths.resultsFile(
                longLatencyOnly ? LONG_LATENCY_OUTPUT_FILE : (noMplLimit ? NO_MPL_OUTPUT_FILE : OUTPUT_FILE));

        ExperimentResult baseline = runner.run("acid_single_model_single_processor_baseline",
                withProcessors(withModels(withSemanticOverlap(base, 0.0), 1), 1));
        double baselineThroughput = baseline.throughput;
        Transaction.resetMetrics();

        if (longLatencyOnly) {
            runLongLatencyOnly(base, runner, outputFile, baselineThroughput);
            System.out.println("Results exported to: " + outputFile);
            return;
        }

        int totalRuns = runCount();
        int currentRun = 0;

        try (PrintWriter writer = new PrintWriter(new FileWriter(outputFile))) {
            writer.println(header());

            for (double overlap : MICRO_OVERLAPS) {
                for (double avg : AVG_FANOUTS) {
                    SimulatorParameters params = withInitialTransactions(
                            withAvgCprs(withMaxCprs(withSemanticOverlap(withModels(base, 10), overlap),
                                    FIXED_MAX_FANOUT), avg),
                            1);
                    currentRun = runAndWrite(writer, runner, currentRun, totalRuns,
                            "single_parent_cascade", "avg_cprs_per_resource", avg, params, baselineThroughput);
                }
            }

            for (int models : MODEL_COUNTS) {
                for (int processors : PROCESSORS) {
                    SimulatorParameters params = withProcessors(withModels(base, models), processors);
                    currentRun = runAndWrite(writer, runner, currentRun, totalRuns,
                            "parallelism_state", "processors", processors, params, baselineThroughput);
                }
            }

            for (int models : MODEL_COUNTS) {
                for (int intensity : WORKLOAD_INTENSITIES) {
                    SimulatorParameters params = withInitialTransactions(withModels(base, models), intensity);
                    currentRun = runAndWrite(writer, runner, currentRun, totalRuns,
                            "workload_intensity", "initial_transactions", intensity, params, baselineThroughput);
                }
            }

            for (int models : MODEL_COUNTS) {
                for (int terminals : TERMINAL_COUNTS) {
                    SimulatorParameters params = withTerminals(withInitialTransactions(withModels(base, models), 0),
                            terminals);
                    currentRun = runAndWrite(writer, runner, currentRun, totalRuns,
                            "terminal_intensity", "num_terminals", terminals, params, baselineThroughput);
                }
            }

            for (int models : MODEL_COUNTS) {
                for (double avg : AVG_FANOUTS) {
                    SimulatorParameters params = withAvgCprs(withMaxCprs(withModels(base, models), FIXED_MAX_FANOUT), avg);
                    currentRun = runAndWriteLifecycleAndLatency(writer, runner, currentRun, totalRuns,
                            "avg_cprs_per_resource", avg, params, baselineThroughput);
                }
            }

            for (double overlap : MICRO_OVERLAPS) {
                for (double avg : AVG_FANOUTS) {
                    SimulatorParameters params = withAvgCprs(
                            withMaxCprs(withSemanticOverlap(withModels(base, 30), overlap), FIXED_MAX_FANOUT),
                            avg);
                    currentRun = runAndWriteLatencyOverlap(writer, runner, currentRun, totalRuns,
                            "avg_cprs_per_resource", avg, params, baselineThroughput);
                }
            }

            for (int models : MODEL_COUNTS) {
                for (double overlap : SEMANTIC_OVERLAPS) {
                    SimulatorParameters params = withSemanticOverlap(withModels(base, models), overlap);
                    currentRun = runAndWrite(writer, runner, currentRun, totalRuns,
                            "lock_contention_attribution", "semantic_overlap", overlap, params, baselineThroughput);
                }
            }

            for (int models : MODEL_COUNTS) {
                for (int maxFanout : MAX_FANOUTS) {
                    SimulatorParameters params = withMaxCprs(
                            withAvgCprs(withModels(base, models), Math.min(2.0, maxFanout)),
                            maxFanout);
                    currentRun = runAndWrite(writer, runner, currentRun, totalRuns,
                            "max_fanout", "max_cprs_per_resource", maxFanout, params, baselineThroughput);
                }
            }
        }

        System.out.println("Results exported to: " + outputFile);
    }

    private static void runLongLatencyOnly(SimulatorParameters base, SimulationRunner runner, String outputFile,
            double baselineThroughput) throws IOException {
        int totalRuns = MODEL_COUNTS.length * AVG_FANOUTS.length + MICRO_OVERLAPS.length * AVG_FANOUTS.length;
        int currentRun = 0;
        try (PrintWriter writer = new PrintWriter(new FileWriter(outputFile))) {
            writer.println(header());
            for (int models : MODEL_COUNTS) {
                for (double avg : AVG_FANOUTS) {
                    SimulatorParameters params = withAvgCprs(withMaxCprs(withModels(base, models), FIXED_MAX_FANOUT),
                            avg);
                    currentRun = runAndWriteLifecycleAndLatency(writer, runner, currentRun, totalRuns,
                            "avg_cprs_per_resource", avg, params, baselineThroughput);
                }
            }
            for (double overlap : MICRO_OVERLAPS) {
                for (double avg : AVG_FANOUTS) {
                    SimulatorParameters params = withAvgCprs(
                            withMaxCprs(withSemanticOverlap(withModels(base, 30), overlap), FIXED_MAX_FANOUT),
                            avg);
                    currentRun = runAndWriteLatencyOverlap(writer, runner, currentRun, totalRuns,
                            "avg_cprs_per_resource", avg, params, baselineThroughput);
                }
            }
        }
    }

    private static int runCount() {
        return MICRO_OVERLAPS.length * AVG_FANOUTS.length
                + MODEL_COUNTS.length * PROCESSORS.length
                + MODEL_COUNTS.length * WORKLOAD_INTENSITIES.length
                + MODEL_COUNTS.length * TERMINAL_COUNTS.length
                + MODEL_COUNTS.length * AVG_FANOUTS.length
                + MICRO_OVERLAPS.length * AVG_FANOUTS.length
                + MODEL_COUNTS.length * SEMANTIC_OVERLAPS.length
                + MODEL_COUNTS.length * MAX_FANOUTS.length;
    }

    private static int runAndWrite(PrintWriter writer, SimulationRunner runner, int currentRun, int totalRuns,
            String experiment, String parameter, Object value, SimulatorParameters params, double baselineThroughput) {
        int runNumber = currentRun + 1;
        System.out.printf(Locale.US, "[%d/%d] CPR impact: %s %s=%s models=%d%n",
                runNumber, totalRuns, experiment, parameter, value, params.totalModels);
        writeAliasRow(writer, experiment, parameter, value, params, runner, baselineThroughput,
                experiment + "_m" + params.totalModels + "_" + parameter + "_" + value);
        Transaction.resetMetrics();
        writer.flush();
        return runNumber;
    }

    private static int runAndWriteLifecycleAndLatency(PrintWriter writer, SimulationRunner runner, int currentRun,
            int totalRuns, String parameter, Object value, SimulatorParameters params, double baselineThroughput) {
        int runNumber = currentRun + 1;
        System.out.printf(Locale.US, "[%d/%d] CPR impact: bookkeeper_lifecycle+latency_sync_delay %s=%s models=%d%n",
                runNumber, totalRuns, parameter, value, params.totalModels);
        String runName = "bookkeeper_lifecycle_m" + params.totalModels + "_" + parameter + "_" + value;
        try {
            ExperimentResult result = runner.run(runName, params);
            writeRow(writer, "bookkeeper_lifecycle", parameter, value, params, result, baselineThroughput, "OK");
            writeRow(writer, "latency_sync_delay", parameter, value, params, result, baselineThroughput, "OK");
        } catch (SimulationWatchdogException e) {
            System.out.printf(Locale.US, "  Watchdog failure recorded for lifecycle/latency %s=%s: %s%n",
                    parameter, value, e.getMessage());
            writeWatchdogRow(writer, "bookkeeper_lifecycle", parameter, value, params, baselineThroughput);
            writeWatchdogRow(writer, "latency_sync_delay", parameter, value, params, baselineThroughput);
        } finally {
            Transaction.resetMetrics();
            writer.flush();
        }
        return runNumber;
    }

    private static int runAndWriteLatencyOverlap(PrintWriter writer, SimulationRunner runner, int currentRun,
            int totalRuns, String parameter, Object value, SimulatorParameters params, double baselineThroughput) {
        int runNumber = currentRun + 1;
        System.out.printf(Locale.US, "[%d/%d] CPR impact: latency_sync_overlap %s=%s models=%d overlap=%.2f%n",
                runNumber, totalRuns, parameter, value, params.totalModels, params.semanticOverlap);
        String runName = String.format(Locale.US, "latency_sync_overlap_m%d_overlap_%.2f_%s_%s",
                params.totalModels, params.semanticOverlap, parameter, value);
        writeAliasRow(writer, "latency_sync_overlap", parameter, value, params, runner, baselineThroughput, runName);
        Transaction.resetMetrics();
        writer.flush();
        return runNumber;
    }

    private static void writeAliasRow(PrintWriter writer, String experiment, String parameter, Object value,
            SimulatorParameters params, SimulationRunner runner, double baselineThroughput, String runName) {
        try {
            ExperimentResult result = runner.run(runName, params);
            writeRow(writer, experiment, parameter, value, params, result, baselineThroughput, "OK");
        } catch (SimulationWatchdogException e) {
            System.out.printf(Locale.US, "  Watchdog failure recorded for %s %s=%s: %s%n",
                    experiment, parameter, value, e.getMessage());
            writeWatchdogRow(writer, experiment, parameter, value, params, baselineThroughput);
        }
    }

    private static String header() {
        return "experiment,parameter,value,model_count,processors,initial_transactions,semantic_overlap,"
                + "max_cprs_per_resource,avg_cprs_per_resource,completed,throughput_tps,baseline_tps,"
                + "relative_throughput,bookkeepers,total_bookkeepers_counter,aborted_groups,"
                + "static_cpr_source_count,static_cpr_edge_count,static_mean_cprs_per_active_source,"
                + "static_mean_transitive_reachable,static_max_transitive_reachable,"
                + "static_mean_indegree,static_max_indegree,"
                + "static_indegree_gini,bookkeepers_created,bookkeepers_completed,bookkeepers_aborted,"
                + "bookkeepers_unfinished,groups_started,groups_completed,peak_pending_queue,"
                + "peak_blocked_queue,peak_ready_queue,peak_lock_queue,peak_io_queue,peak_cpu_queue,"
                + "peak_rollback_queue,peak_waiting_for_group_queue,queue_sample_count,p95_pending_queue,p95_blocked_queue,"
                + "p95_lock_queue,p95_waiting_for_group_queue,p95_rollback_queue,peak_pending_state,peak_running_state,peak_blocked_state,"
                + "peak_ready_state,peak_completed_state,parent_latency_samples,parent_latency_mean_ms,"
                + "parent_latency_p50_ms,parent_latency_p95_ms,parent_latency_p99_ms,parent_latency_max_ms,"
                + "cpr_sync_delay_samples,cpr_sync_delay_mean_ms,cpr_sync_delay_p50_ms,"
                + "cpr_sync_delay_p95_ms,cpr_sync_delay_p99_ms,cpr_sync_delay_max_ms,"
                + "rollback_latency_samples,rollback_latency_mean_ms,rollback_latency_p50_ms,"
                + "rollback_latency_p95_ms,rollback_latency_p99_ms,rollback_latency_max_ms,"
                + "cascade_amplification_samples,cascade_amplification_mean,cascade_amplification_p50,"
                + "cascade_amplification_p95,cascade_amplification_p99,cascade_amplification_max,"
                + "cascade_resource_reference_samples,mean_parent_resource_refs,mean_cpr_resource_refs,"
                + "started_parent_resource_reference_samples,mean_started_parent_resource_refs,"
                + "max_runtime_depth,status";
    }

    private static void writeRow(PrintWriter writer, String experiment, String parameter, Object value,
            SimulatorParameters params, ExperimentResult result, double baselineThroughput, String status) {
        CprGraphMetrics staticMetrics = staticGraphMetrics(params);
        double relative = baselineThroughput == 0.0 ? 0.0 : result.throughput / baselineThroughput;
        int unfinishedBookkeepers = Math.max(0,
                result.bookkeepersCreatedTotal - result.bookkeepersCompletedTotal - result.bookkeepersAbortedTotal);

        writer.printf(Locale.US,
                "%s,%s,%s,%d,%d,%d,%.4f,%d,%.4f,%d,%.6f,%.6f,%.6f,%d,%d,%d,"
                        + "%d,%d,%.6f,%.6f,%d,%.6f,%d,%.6f,"
                        + "%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%.6f,%.6f,%.6f,%.6f,%.6f,%d,%d,%d,%d,%d,"
                        + "%d,%.6f,%.6f,%.6f,%.6f,%.6f,"
                        + "%d,%.6f,%.6f,%.6f,%.6f,%.6f,"
                        + "%d,%.6f,%.6f,%.6f,%.6f,%.6f,"
                        + "%d,%.6f,%.6f,%.6f,%.6f,%.6f,%d,%.6f,%.6f,%d,%.6f,%d,%s%n",
                experiment, parameter, value, params.totalModels, params.numberOfIoProcessors,
                params.initialTransactions, params.semanticOverlap, params.maxCprsPerResource,
                params.avgCprsPerResource, result.completedTransactions, result.throughput,
                baselineThroughput, relative, result.bookkeepersCreatedTotal, result.totalBookkeepers,
                result.totalAbortedGroups, staticMetrics.sources(), staticMetrics.edges(),
                staticMetrics.meanCprsPerActiveSource(), staticMetrics.meanTransitiveReachableResources(),
                staticMetrics.maxTransitiveReachableResources(),
                staticMetrics.meanInDegree(), staticMetrics.maxInDegree(),
                staticMetrics.inDegreeGini(), result.bookkeepersCreatedTotal, result.bookkeepersCompletedTotal,
                result.bookkeepersAbortedTotal, unfinishedBookkeepers, result.groupsStartedTotal,
                result.groupsCompletedTotal, result.peakPendingQueueDepth, result.peakBlockedQueueDepth,
                result.peakReadyQueueDepth, result.peakLockQueueDepth, result.peakIoQueueDepth,
                result.peakCpuQueueDepth, result.peakRollbackQueueDepth, result.peakWaitingForGroupQueueDepth,
                result.queueDepthSampleCount,
                result.p95PendingQueueDepth, result.p95BlockedQueueDepth, result.p95LockQueueDepth,
                result.p95WaitingForGroupQueueDepth, result.p95RollbackQueueDepth, result.peakPendingStateCount,
                result.peakRunningStateCount, result.peakBlockedStateCount, result.peakReadyStateCount,
                result.peakCompletedStateCount, result.parentLatencySampleCount, result.parentLatencyMean,
                result.parentLatencyP50, result.parentLatencyP95, result.parentLatencyP99,
                result.parentLatencyMax, result.cprSyncDelaySampleCount, result.cprSyncDelayMean,
                result.cprSyncDelayP50, result.cprSyncDelayP95, result.cprSyncDelayP99,
                result.cprSyncDelayMax, result.rollbackLatencySampleCount, result.rollbackLatencyMean,
                result.rollbackLatencyP50, result.rollbackLatencyP95, result.rollbackLatencyP99,
                result.rollbackLatencyMax, result.cascadeAmplificationSampleCount,
                result.cascadeAmplificationMean, result.cascadeAmplificationP50,
                result.cascadeAmplificationP95, result.cascadeAmplificationP99,
                result.cascadeAmplificationMax, result.cascadeResourceReferenceSampleCount,
                result.meanCascadeParentResourceReferences, result.meanCascadeCprResourceReferences,
                result.startedGroupParentResourceReferenceSampleCount,
                result.meanStartedGroupParentResourceReferences,
                result.maxDepthObserved, status);
    }

    private static void writeWatchdogRow(PrintWriter writer, String experiment, String parameter, Object value,
            SimulatorParameters params, double baselineThroughput) {
        ExperimentResult empty = new ExperimentResult("watchdog", params.totalModels, params.totalResources,
                params.numberOfIoProcessors, params.numberOfCpuProcessors, params.totalLocks,
                params.initialTransactions, params.simulationDurationSeconds, 0, 0, 0, 0, 0,
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, null, 0, 0, 0, 0, 0, 0, null, null, 0);
        writeRow(writer, experiment, parameter, value, params, empty, baselineThroughput, "WATCHDOG");
    }

    private static CprGraphMetrics staticGraphMetrics(SimulatorParameters params) {
        ModelFactory modelFactory = new ModelFactory(params.instancePoolSize);
        List<Model> modelPool = modelFactory.createModels(params.propertiesPerModel);
        CprSchema schema = new CprSchemaBuilder(
                modelPool, params.cprTopologyMode, params.semanticOverlap,
                params.avgCprsPerResource, params.maxCprsPerResource, params.semanticOverlapSeed, null, false)
            .build();
        int totalProperties = modelPool.stream().mapToInt(Model::propertyCount).sum();
        return CprGraphMetrics.from(schema, totalProperties);
    }

    public static SimulatorParameters baseParameters() {
        return SimulatorParameters.builder()
                .totalModels(10)
                .totalResources(5000)
                .ioTimePerResource(200)
                .cpuTimePerResource(100)
                .lockIOTimePerResource(200)
                .lockCPUTimePerResource(100)
                .initialTransactions(100)
                .totalTransactionSteps(10000)
                .maxTransactionModels(5)
                .maxTransactionResources(5)
                .useConstantTransactionSize(false)
                .useRandomResourceSelection(false)
                .semanticOverlap(FIXED_OVERLAP)
                .maxCprsPerResource(FIXED_MAX_FANOUT)
                .avgCprsPerResource(FIXED_AVG_FANOUT)
                .referentialAction(ReferentialAction.ON_CASCADE)
                .concurrencyModel(ConcurrencyModel.ACID)
                .simulationDurationSeconds(SIMULATION_SECONDS)
                .numberOfIoProcessors(FIXED_PROCESSORS)
                .numberOfCpuProcessors(FIXED_PROCESSORS)
                .numberOfReconcilerProcessors(0)
                .totalLocks(5000)
                .transactionSamplingSeed(12345L)
                .semanticOverlapSeed(67890L)
                .build();
    }

    private static SimulatorParameters withModels(SimulatorParameters base, int modelCount) {
        return copyBuilder(base)
                .totalModels(modelCount)
                .maxTransactionModels(Math.min(base.maxTransactionModels, modelCount))
                .build();
    }

    private static SimulatorParameters withProcessors(SimulatorParameters base, int processors) {
        return copyBuilder(base)
                .numberOfIoProcessors(processors)
                .numberOfCpuProcessors(processors)
                .build();
    }

    private static SimulatorParameters withInitialTransactions(SimulatorParameters base, int initialTransactions) {
        return copyBuilder(base)
                .initialTransactions(initialTransactions)
                .build();
    }

    private static SimulatorParameters withTerminals(SimulatorParameters base, int terminals) {
        return copyBuilder(base)
                .transactionMode(TransactionMode.TERMINAL)
                .numTerminals(terminals)
                .build();
    }

    private static SimulatorParameters withSemanticOverlap(SimulatorParameters base, double semanticOverlap) {
        return copyBuilder(base)
                .semanticOverlap(semanticOverlap)
                .build();
    }

    private static SimulatorParameters withMaxCprs(SimulatorParameters base, int maxCprs) {
        return copyBuilder(base)
                .maxCprsPerResource(maxCprs)
                .build();
    }

    private static SimulatorParameters withAvgCprs(SimulatorParameters base, double avgCprs) {
        return copyBuilder(base)
                .avgCprsPerResource(avgCprs)
                .build();
    }

    /**
     * Delegates to {@link SeedSweepRunner#copyBuilder}.
     *
     * <p>ONE copy list, not a hand-maintained one per experiment. Builder.build() substitutes a
     * default for anything left unset, so a field omitted from a copy is not an error but a
     * different simulation - which has already cost one confirmed bug here, propertiesPerModel
     * and instancePoolSize missing from a copy so that an A/B arm ran D=4950 against an intended
     * 4800, caught only by a suspiciously identical rerun. Delegation means adding a field to
     * SimulatorParameters cannot silently bypass any of these call sites.
     */
    private static SimulatorParameters.Builder copyBuilder(SimulatorParameters params) {
        return SeedSweepRunner.copyBuilder(params);
    }
}
