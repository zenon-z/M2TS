package de.tum.tssimulator.experiments;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.List;
import java.util.Locale;

import de.tum.tssimulator.bookkeeping.CprGraphMetrics;
import de.tum.tssimulator.bootstrap.SimulationWatchdogException;
import de.tum.tssimulator.config.SimulatorParameters;
import de.tum.tssimulator.core.Model;
import de.tum.tssimulator.core.ModelFactory;
import de.tum.tssimulator.core.Transaction;
import de.tum.tssimulator.cpr.CprSchema;
import de.tum.tssimulator.cpr.CprSchemaBuilder;
import de.tum.tssimulator.experiments.support.ExperimentOutputPaths;

/**
 * Full ACID CPR parameter grid sweep.
 *
 * Runs all valid combinations of:
 * - CPR source fraction, backed by semanticOverlap
 * - maxCprsPerResource
 * - avgCprsPerResource
 *
 * Combinations with avgCprsPerResource > maxCprsPerResource are invalid and
 * skipped by construction.
 */
public class CprParameterGridAcidThroughputExperiment {
    private static final String OUTPUT_FILE = "cpr_parameter_grid_acid_throughput.csv";

    private static final double[] CPR_FRACTIONS = {0.00, 0.05, 0.10, 0.20, 0.30, 0.50};
    private static final int[] MAX_CPRS_PER_RESOURCE = {2, 3, 5, 8, 10};
    private static final double[] AVG_CPRS_PER_RESOURCE = {1.0, 2.0, 4.0, 6.0, 8.0};
    private static final long[] CPR_SEEDS = {67890L, 67891L, 67892L, 67893L, 67894L};

    public static void main(String[] args) throws IOException {
        SimulatorParameters base = CprParameterAcidThroughputExperiment.baseParameters();
        SimulationRunner runner = new SimulationRunner();
        String outputFile = ExperimentOutputPaths.resultsFile(OUTPUT_FILE);

        int totalRuns = validCombinationCount();
        int currentRun = 0;

        try (PrintWriter writer = new PrintWriter(new FileWriter(outputFile))) {
            writer.println("replication,cpr_seed,fraction_with_cpr,max_cprs_per_resource,avg_cprs_per_resource,"
                    + "static_cpr_sources,static_cpr_edges,mean_cprs_per_active_source,"
                    + "mean_transitive_reachable_resources,max_transitive_reachable_resources,"
                    + "mean_in_degree,max_in_degree,in_degree_gini,in_degree_nonzero_resources,"
                    + "completed,bookkeepers,aborted_groups,throughput_tps,status");

            for (int replication = 0; replication < CPR_SEEDS.length; replication++) {
                long cprSeed = CPR_SEEDS[replication];
                for (double fraction : CPR_FRACTIONS) {
                    for (int maxCprs : MAX_CPRS_PER_RESOURCE) {
                        for (double avgCprs : AVG_CPRS_PER_RESOURCE) {
                            if (avgCprs > maxCprs) {
                                continue;
                            }

                            currentRun++;
                            SimulatorParameters params = createParameters(base, fraction, maxCprs, avgCprs, cprSeed);
                            CprGraphMetrics graphMetrics = graphMetrics(params);
                            String experimentName = String.format(Locale.US,
                                    "cpr_grid_rep%d_f%.2f_max%d_avg%.1f",
                                    replication + 1, fraction, maxCprs, avgCprs);

                            System.out.printf(Locale.US,
                                    "[%d/%d] ACID CPR grid: rep=%d, seed=%d, fraction=%.2f, max=%d, avg=%.1f%n",
                                    currentRun, totalRuns, replication + 1, cprSeed, fraction, maxCprs, avgCprs);

                            try {
                                ExperimentResult result = runner.run(experimentName, params);
                                writeRow(writer, replication + 1, cprSeed, params, graphMetrics,
                                        result.completedTransactions, result.totalBookkeepers,
                                        result.totalAbortedGroups, result.throughput, "OK");
                            } catch (SimulationWatchdogException e) {
                                System.out.printf(Locale.US,
                                        "  Watchdog failure recorded for rep=%d, fraction=%.2f, max=%d, avg=%.1f: %s%n",
                                        replication + 1, fraction, maxCprs, avgCprs, e.getMessage());
                                writeRow(writer, replication + 1, cprSeed, params, graphMetrics,
                                        0, -1, -1, 0.0, "WATCHDOG");
                            } finally {
                                writer.flush();
                                Transaction.resetMetrics();
                            }
                        }
                    }
                }
            }
        }

        System.out.println("Results exported to: " + outputFile);
    }

    public static double[] cprFractions() {
        return CPR_FRACTIONS.clone();
    }

    public static int[] maxCprsPerResourceValues() {
        return MAX_CPRS_PER_RESOURCE.clone();
    }

    public static double[] avgCprsPerResourceValues() {
        return AVG_CPRS_PER_RESOURCE.clone();
    }

    public static int validCombinationCount() {
        int count = 0;
        for (double ignoredFraction : CPR_FRACTIONS) {
            for (int maxCprs : MAX_CPRS_PER_RESOURCE) {
                for (double avgCprs : AVG_CPRS_PER_RESOURCE) {
                    if (avgCprs <= maxCprs) {
                        count++;
                    }
                }
            }
        }
        return count * CPR_SEEDS.length;
    }

    public static int uniqueParameterCombinationCount() {
        int count = 0;
        for (double ignoredFraction : CPR_FRACTIONS) {
            for (int maxCprs : MAX_CPRS_PER_RESOURCE) {
                for (double avgCprs : AVG_CPRS_PER_RESOURCE) {
                    if (avgCprs <= maxCprs) {
                        count++;
                    }
                }
            }
        }
        return count;
    }

    public static long[] cprSeeds() {
        return CPR_SEEDS.clone();
    }

    public static SimulatorParameters createParameters(
            SimulatorParameters base,
            double fractionWithCpr,
            int maxCprsPerResource,
            double avgCprsPerResource) {
        return SimulatorParameters.builder()
                .totalModels(base.totalModels)
                .totalResources(base.totalResources)
                .ioTimePerResource(base.ioTimePerResource)
                .cpuTimePerResource(base.cpuTimePerResource)
                .lockIOTimePerResource(base.lockIOTimePerResource)
                .lockCPUTimePerResource(base.lockCPUTimePerResource)
                .initialTransactions(base.initialTransactions)
                .totalTransactionSteps(base.totalTransactionSteps)
                .maxTransactionModels(base.maxTransactionModels)
                .maxTransactionResources(base.maxTransactionResources)
                .useConstantTransactionSize(base.useConstantTransactionSize)
                .constantTransactionSize(base.constantTransactionSize)
                .useRandomResourceSelection(base.useRandomResourceSelection)
                .semanticOverlap(fractionWithCpr)
                .maxCprsPerResource(maxCprsPerResource)
                .avgCprsPerResource(avgCprsPerResource)
                .referentialAction(base.referentialAction)
                .concurrencyModel(base.concurrencyModel)
                .simulationDurationSeconds(base.simulationDurationSeconds)
                .numberOfIoProcessors(base.numberOfIoProcessors)
                .numberOfCpuProcessors(base.numberOfCpuProcessors)
                .numberOfReconcilerProcessors(base.numberOfReconcilerProcessors)
                .totalLocks(base.totalLocks)
                .transactionSamplingSeed(base.transactionSamplingSeed)
                .semanticOverlapSeed(base.semanticOverlapSeed)
                .stepTimeSlice(base.stepTimeSlice)
                .transactionMode(base.transactionMode)
                .numTerminals(base.numTerminals)
                .meanInterArrivalDelay(base.meanInterArrivalDelay)
                .build();
    }

    public static SimulatorParameters createParameters(
            SimulatorParameters base,
            double fractionWithCpr,
            int maxCprsPerResource,
            double avgCprsPerResource,
            long cprSeed) {
        SimulatorParameters params = createParameters(base, fractionWithCpr, maxCprsPerResource, avgCprsPerResource);
        return SimulatorParameters.builder()
                .totalModels(params.totalModels)
                .totalResources(params.totalResources)
                .ioTimePerResource(params.ioTimePerResource)
                .cpuTimePerResource(params.cpuTimePerResource)
                .lockIOTimePerResource(params.lockIOTimePerResource)
                .lockCPUTimePerResource(params.lockCPUTimePerResource)
                .initialTransactions(params.initialTransactions)
                .totalTransactionSteps(params.totalTransactionSteps)
                .maxTransactionModels(params.maxTransactionModels)
                .maxTransactionResources(params.maxTransactionResources)
                .useConstantTransactionSize(params.useConstantTransactionSize)
                .constantTransactionSize(params.constantTransactionSize)
                .useRandomResourceSelection(params.useRandomResourceSelection)
                .semanticOverlap(params.semanticOverlap)
                .maxCprsPerResource(params.maxCprsPerResource)
                .avgCprsPerResource(params.avgCprsPerResource)
                .cprTopologyMode(params.cprTopologyMode)
                .referentialAction(params.referentialAction)
                .concurrencyModel(params.concurrencyModel)
                .simulationDurationSeconds(params.simulationDurationSeconds)
                .numberOfIoProcessors(params.numberOfIoProcessors)
                .numberOfCpuProcessors(params.numberOfCpuProcessors)
                .numberOfReconcilerProcessors(params.numberOfReconcilerProcessors)
                .totalLocks(params.totalLocks)
                .transactionSamplingSeed(params.transactionSamplingSeed)
                .semanticOverlapSeed(cprSeed)
                .stepTimeSlice(params.stepTimeSlice)
                .transactionMode(params.transactionMode)
                .numTerminals(params.numTerminals)
                .meanInterArrivalDelay(params.meanInterArrivalDelay)
                .build();
    }

    private static CprGraphMetrics graphMetrics(SimulatorParameters params) {
        ModelFactory modelFactory = new ModelFactory(params.instancePoolSize);
        List<Model> models = modelFactory.createModels(params.propertiesPerModel);
        CprSchema schema = new CprSchemaBuilder(
                models, params.cprTopologyMode, params.semanticOverlap,
                params.avgCprsPerResource, params.maxCprsPerResource, params.semanticOverlapSeed, null, false)
            .build();
        int totalProperties = models.stream().mapToInt(Model::propertyCount).sum();
        return CprGraphMetrics.from(schema, totalProperties);
    }

    private static void writeRow(PrintWriter writer, int replication, long cprSeed, SimulatorParameters params,
            CprGraphMetrics stats, long completed, int bookkeepers, int abortedGroups, double throughput,
            String status) {
        writer.printf(Locale.US,
                "%d,%d,%.4f,%d,%.4f,%d,%d,%.4f,%.4f,%d,%.4f,%d,%.6f,%d,%d,%d,%d,%.6f,%s%n",
                replication,
                cprSeed,
                params.semanticOverlap,
                params.maxCprsPerResource,
                params.avgCprsPerResource,
                stats.sources(),
                stats.edges(),
                stats.meanCprsPerActiveSource(),
                stats.meanTransitiveReachableResources(),
                stats.maxTransitiveReachableResources(),
                stats.meanInDegree(),
                stats.maxInDegree(),
                stats.inDegreeGini(),
                stats.inDegreeNonzeroResources(),
                completed,
                bookkeepers,
                abortedGroups,
                throughput,
                status);
    }
}
