package de.tum.tssimulator.experiments;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Locale;

import de.tum.tssimulator.bootstrap.SimulationWatchdogException;
import de.tum.tssimulator.config.SimulatorParameters;
import de.tum.tssimulator.core.Transaction;
import de.tum.tssimulator.experiments.support.ExperimentOutputPaths;
import de.tum.tssimulator.experiments.support.SeedSweepRunner;

/**
 * ACID sensitivity sweeps normalized to a single-model, single-processor baseline.
 */
public class CprRelativeThroughputSensitivityExperiment {
    private static final String OUTPUT_FILE = "cpr_relative_throughput_sensitivity.csv";

    private static final int[] MODEL_COUNTS = {5, 10, 30};
    private static final int[] PROCESSOR_COUNTS = {1, 2, 4, 8, 15, 30};
    private static final double[] SEMANTIC_OVERLAPS = {0.00, 0.05, 0.10, 0.20, 0.30, 0.50};
    private static final double[] AVG_CPRS_PER_RESOURCE = {1.0, 2.0, 4.0, 6.0, 8.0};
    private static final int[] MAX_CPRS_PER_RESOURCE = {2, 3, 5, 8, 10};
    private static final double FIXED_SEMANTIC_OVERLAP = 0.30;

    public static void main(String[] args) throws IOException {
        SimulatorParameters base = withSemanticOverlap(
                CprParameterAcidThroughputExperiment.baseParameters(),
                FIXED_SEMANTIC_OVERLAP);
        SimulationRunner runner = new SimulationRunner();
        String outputFile = ExperimentOutputPaths.resultsFile(OUTPUT_FILE);

        SimulatorParameters baselineParams = withProcessors(withModels(base, 1), 1);
        ExperimentResult baseline = runner.run("single_model_single_processor_baseline", baselineParams);
        double baselineThroughput = baseline.throughput;
        Transaction.resetMetrics();

        int totalRuns = 1 + processorRunCount() + SEMANTIC_OVERLAPS.length * MODEL_COUNTS.length
                + AVG_CPRS_PER_RESOURCE.length * MODEL_COUNTS.length
                + MAX_CPRS_PER_RESOURCE.length * MODEL_COUNTS.length;
        int currentRun = 1;

        try (PrintWriter writer = new PrintWriter(new FileWriter(outputFile))) {
            writer.println("sweep,model_count,processors,semantic_overlap,max_cprs_per_resource,"
                    + "avg_cprs_per_resource,value,completed,bookkeepers,aborted_groups,"
                    + "throughput_tps,baseline_tps,relative_throughput,status");
            writeRow(writer, "baseline", baselineParams, 1.0, baseline, baselineThroughput, "OK");

            for (int modelCount : MODEL_COUNTS) {
                for (int processors : PROCESSOR_COUNTS) {
                    currentRun++;
                    SimulatorParameters params = withProcessors(withModels(base, modelCount), processors);
                    runAndWrite(writer, runner, currentRun, totalRuns, "processors", processors,
                            params, baselineThroughput);
                }
            }

            for (int modelCount : MODEL_COUNTS) {
                for (double semanticOverlap : SEMANTIC_OVERLAPS) {
                    currentRun++;
                    SimulatorParameters params = withSemanticOverlap(withModels(base, modelCount), semanticOverlap);
                    runAndWrite(writer, runner, currentRun, totalRuns, "semantic_overlap", semanticOverlap,
                            params, baselineThroughput);
                }
            }

            for (int modelCount : MODEL_COUNTS) {
                for (double avgCprs : AVG_CPRS_PER_RESOURCE) {
                    currentRun++;
                    SimulatorParameters params = withAvgCprs(withMaxCprs(withModels(base, modelCount), 10), avgCprs);
                    runAndWrite(writer, runner, currentRun, totalRuns, "avg_cprs_per_resource", avgCprs,
                            params, baselineThroughput);
                }
            }

            for (int modelCount : MODEL_COUNTS) {
                for (int maxCprs : MAX_CPRS_PER_RESOURCE) {
                    currentRun++;
                    SimulatorParameters params = withAvgCprs(withMaxCprs(withModels(base, modelCount), maxCprs), 2.0);
                    runAndWrite(writer, runner, currentRun, totalRuns, "max_cprs_per_resource", maxCprs,
                            params, baselineThroughput);
                }
            }
        }

        System.out.println("Results exported to: " + outputFile);
    }

    public static int[] modelCounts() {
        return MODEL_COUNTS.clone();
    }

    public static int[] processorCounts() {
        return PROCESSOR_COUNTS.clone();
    }

    public static double[] semanticOverlaps() {
        return SEMANTIC_OVERLAPS.clone();
    }

    public static double[] avgCprsPerResourceValues() {
        return AVG_CPRS_PER_RESOURCE.clone();
    }

    public static int[] maxCprsPerResourceValues() {
        return MAX_CPRS_PER_RESOURCE.clone();
    }

    public static double fixedSemanticOverlap() {
        return FIXED_SEMANTIC_OVERLAP;
    }

    public static int uniqueRunCount() {
        return 1 + processorRunCount() + SEMANTIC_OVERLAPS.length * MODEL_COUNTS.length
                + AVG_CPRS_PER_RESOURCE.length * MODEL_COUNTS.length
                + MAX_CPRS_PER_RESOURCE.length * MODEL_COUNTS.length;
    }

    private static int processorRunCount() {
        return PROCESSOR_COUNTS.length * MODEL_COUNTS.length;
    }

    private static void runAndWrite(PrintWriter writer, SimulationRunner runner, int currentRun, int totalRuns,
            String sweep, Object value, SimulatorParameters params, double baselineThroughput) {
        String experimentName = String.format(Locale.US, "%s_m%d_%s",
                sweep, params.totalModels, value);
        System.out.printf(Locale.US, "[%d/%d] Relative throughput sweep: %s=%s, models=%d%n",
                currentRun, totalRuns, sweep, value, params.totalModels);

        try {
            ExperimentResult result = runner.run(experimentName, params);
            writeRow(writer, sweep, params, value, result, baselineThroughput, "OK");
        } catch (SimulationWatchdogException e) {
            System.out.printf(Locale.US, "  Watchdog failure recorded for %s=%s, models=%d: %s%n",
                    sweep, value, params.totalModels, e.getMessage());
            writeWatchdogRow(writer, sweep, params, value, baselineThroughput);
        } finally {
            writer.flush();
            Transaction.resetMetrics();
        }
    }

    private static void writeRow(PrintWriter writer, String sweep, SimulatorParameters params, Object value,
            ExperimentResult result, double baselineThroughput, String status) {
        double relative = baselineThroughput == 0.0 ? 0.0 : result.throughput / baselineThroughput;
        writer.printf(Locale.US, "%s,%d,%d,%.4f,%d,%.4f,%s,%d,%d,%d,%.6f,%.6f,%.6f,%s%n",
                sweep,
                params.totalModels,
                params.numberOfIoProcessors,
                params.semanticOverlap,
                params.maxCprsPerResource,
                params.avgCprsPerResource,
                value,
                result.completedTransactions,
                result.totalBookkeepers,
                result.totalAbortedGroups,
                result.throughput,
                baselineThroughput,
                relative,
                status);
    }

    private static void writeWatchdogRow(PrintWriter writer, String sweep, SimulatorParameters params, Object value,
            double baselineThroughput) {
        writer.printf(Locale.US, "%s,%d,%d,%.4f,%d,%.4f,%s,%d,%d,%d,%.6f,%.6f,%.6f,WATCHDOG%n",
                sweep,
                params.totalModels,
                params.numberOfIoProcessors,
                params.semanticOverlap,
                params.maxCprsPerResource,
                params.avgCprsPerResource,
                value,
                0,
                -1,
                -1,
                0.0,
                baselineThroughput,
                0.0);
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
