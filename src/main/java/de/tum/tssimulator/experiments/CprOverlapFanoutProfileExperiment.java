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
 * Compare line CPR graphs against progressively wider fan-out profiles as
 * semantic overlap increases.
 */
public class CprOverlapFanoutProfileExperiment {
    private static final String OUTPUT_FILE = "cpr_overlap_fanout_profiles.csv";

    private static final int[] MODEL_COUNTS = {5, 10, 30};
    private static final double[] SEMANTIC_OVERLAPS = {0.00, 0.05, 0.10, 0.20, 0.30, 0.50};
    private static final FanoutProfile[] FANOUT_PROFILES = {
            new FanoutProfile("line", 1, 1.0),
            new FanoutProfile("light", 2, 2.0),
            new FanoutProfile("medium", 5, 4.0),
            new FanoutProfile("heavy", 8, 6.0),
            new FanoutProfile("very_heavy", 10, 8.0)
    };

    public static void main(String[] args) throws IOException {
        SimulatorParameters base = CprImpactExperimentSuite.baseParameters();
        SimulationRunner runner = new SimulationRunner();
        String outputFile = ExperimentOutputPaths.resultsFile(OUTPUT_FILE);

        SimulatorParameters baselineParams = withProcessors(withModels(withSemanticOverlap(base, 0.0), 1), 1);
        ExperimentResult baseline = runner.run("overlap_fanout_profile_baseline", baselineParams);
        double baselineThroughput = baseline.throughput;
        Transaction.resetMetrics();

        int totalRuns = MODEL_COUNTS.length * SEMANTIC_OVERLAPS.length * FANOUT_PROFILES.length;
        int currentRun = 0;

        try (PrintWriter writer = new PrintWriter(new FileWriter(outputFile))) {
            writer.println("profile,model_count,semantic_overlap,max_cprs_per_resource,avg_cprs_per_resource,"
                    + "completed,throughput_tps,baseline_tps,relative_throughput,bookkeepers_created,"
                    + "bookkeepers_completed,bookkeepers_unfinished,groups_started,"
                    + "mean_parent_resource_refs,mean_cpr_resource_refs,status");

            for (int modelCount : MODEL_COUNTS) {
                for (FanoutProfile profile : FANOUT_PROFILES) {
                    for (double overlap : SEMANTIC_OVERLAPS) {
                        currentRun++;
                        SimulatorParameters params = withFanoutProfile(
                                withSemanticOverlap(withModels(base, modelCount), overlap),
                                profile);

                        System.out.printf(Locale.US,
                                "[%d/%d] Overlap/fanout profile: profile=%s overlap=%.2f models=%d%n",
                                currentRun, totalRuns, profile.name(), overlap, modelCount);

                        String experimentName = String.format(Locale.US,
                                "overlap_fanout_%s_m%d_o%.2f", profile.name(), modelCount, overlap);
                        try {
                            ExperimentResult result = runner.run(experimentName, params);
                            writeRow(writer, profile, params, result, baselineThroughput, "OK");
                        } catch (SimulationWatchdogException e) {
                            System.out.printf(Locale.US,
                                    "  Watchdog failure recorded for profile=%s overlap=%.2f models=%d: %s%n",
                                    profile.name(), overlap, modelCount, e.getMessage());
                            writeWatchdogRow(writer, profile, params, baselineThroughput);
                        } finally {
                            writer.flush();
                            Transaction.resetMetrics();
                        }
                    }
                }
            }
        }

        System.out.println("Results exported to: " + outputFile);
    }

    public static int[] modelCounts() {
        return MODEL_COUNTS.clone();
    }

    public static double[] semanticOverlaps() {
        return SEMANTIC_OVERLAPS.clone();
    }

    public static FanoutProfile[] fanoutProfiles() {
        return FANOUT_PROFILES.clone();
    }

    public static int uniqueRunCount() {
        return MODEL_COUNTS.length * SEMANTIC_OVERLAPS.length * FANOUT_PROFILES.length;
    }

    private static void writeRow(PrintWriter writer, FanoutProfile profile, SimulatorParameters params,
            ExperimentResult result, double baselineThroughput, String status) {
        double relative = baselineThroughput == 0.0 ? 0.0 : result.throughput / baselineThroughput;
        int unfinishedBookkeepers = Math.max(0,
                result.bookkeepersCreatedTotal - result.bookkeepersCompletedTotal - result.bookkeepersAbortedTotal);
        writer.printf(Locale.US, "%s,%d,%.4f,%d,%.4f,%d,%.6f,%.6f,%.6f,%d,%d,%d,%d,%.6f,%.6f,%s%n",
                profile.name(),
                params.totalModels,
                params.semanticOverlap,
                params.maxCprsPerResource,
                params.avgCprsPerResource,
                result.completedTransactions,
                result.throughput,
                baselineThroughput,
                relative,
                result.bookkeepersCreatedTotal,
                result.bookkeepersCompletedTotal,
                unfinishedBookkeepers,
                result.groupsStartedTotal,
                result.meanStartedGroupParentResourceReferences,
                result.meanCascadeCprResourceReferences,
                status);
    }

    private static void writeWatchdogRow(PrintWriter writer, FanoutProfile profile, SimulatorParameters params,
            double baselineThroughput) {
        writer.printf(Locale.US, "%s,%d,%.4f,%d,%.4f,%d,%.6f,%.6f,%.6f,%d,%d,%d,%d,%.6f,%.6f,WATCHDOG%n",
                profile.name(),
                params.totalModels,
                params.semanticOverlap,
                params.maxCprsPerResource,
                params.avgCprsPerResource,
                0,
                0.0,
                baselineThroughput,
                0.0,
                -1,
                -1,
                -1,
                0,
                0.0,
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

    private static SimulatorParameters withFanoutProfile(SimulatorParameters base, FanoutProfile profile) {
        return copyBuilder(base)
                .maxCprsPerResource(profile.maxCprsPerResource())
                .avgCprsPerResource(profile.avgCprsPerResource())
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

    public record FanoutProfile(String name, int maxCprsPerResource, double avgCprsPerResource) {
    }
}
