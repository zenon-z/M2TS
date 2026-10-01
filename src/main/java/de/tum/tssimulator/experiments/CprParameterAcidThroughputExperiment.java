package de.tum.tssimulator.experiments;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Locale;

import de.tum.tssimulator.bootstrap.SimulationWatchdogException;
import de.tum.tssimulator.config.ConcurrencyModel;
import de.tum.tssimulator.config.SimulatorParameters;
import de.tum.tssimulator.core.Transaction;
import de.tum.tssimulator.experiments.support.ExperimentOutputPaths;
import de.tum.tssimulator.experiments.variation.ParameterVariation;
import de.tum.tssimulator.experiments.variation.VaryAvgCprsPerResource;
import de.tum.tssimulator.experiments.variation.VaryCprFraction;
import de.tum.tssimulator.experiments.variation.VaryMaxCprsPerResource;

/**
 * ACID throughput sweep for the three CPR generation parameters.
 *
 * Each sweep varies one CPR parameter while holding the other two fixed:
 * - fraction of eligible resources with at least one CPR
 * - maximum CPRs per active resource
 * - average CPRs per active resource under the global fan-out model
 */
public class CprParameterAcidThroughputExperiment {
    private static final String OUTPUT_FILE = "cpr_parameter_acid_throughput.csv";

    public static void main(String[] args) throws IOException {
        SimulatorParameters base = baseParameters();
        CprSweep[] sweeps = sweeps();
        SimulationRunner runner = new SimulationRunner();
        String outputFile = ExperimentOutputPaths.resultsFile(OUTPUT_FILE);

        try (PrintWriter writer = new PrintWriter(new FileWriter(outputFile))) {
            writer.println("sweep,parameter,value,fraction_with_cpr,max_cprs_per_resource,avg_cprs_per_resource,"
                    + "completed,bookkeepers,aborted_groups,throughput_tps,status");

            int totalRuns = 0;
            for (CprSweep sweep : sweeps) {
                totalRuns += sweep.variation().getValues().length;
            }

            int currentRun = 0;
            for (CprSweep sweep : sweeps) {
                for (Object value : sweep.variation().getValues()) {
                    currentRun++;
                    SimulatorParameters params = sweep.variation().applyVariation(base, value);
                    String experimentName = sweep.name() + "_" + value;

                    System.out.printf("[%d/%d] ACID CPR sweep: %s=%s%n",
                            currentRun, totalRuns, sweep.parameterName(), value);

                    try {
                        ExperimentResult result = runner.run(experimentName, params);
                        writer.printf(Locale.US, "%s,%s,%s,%.4f,%d,%.4f,%d,%d,%d,%.6f,OK%n",
                                sweep.name(),
                                sweep.parameterName(),
                                value,
                                params.semanticOverlap,
                                params.maxCprsPerResource,
                                params.avgCprsPerResource,
                                result.completedTransactions,
                                result.totalBookkeepers,
                                result.totalAbortedGroups,
                                result.throughput);
                    } catch (SimulationWatchdogException e) {
                        System.out.printf(Locale.US,
                                "  Watchdog failure recorded for %s=%s: %s%n",
                                sweep.parameterName(), value, e.getMessage());
                        writer.printf(Locale.US, "%s,%s,%s,%.4f,%d,%.4f,%d,%d,%d,%.6f,WATCHDOG%n",
                                sweep.name(),
                                sweep.parameterName(),
                                value,
                                params.semanticOverlap,
                                params.maxCprsPerResource,
                                params.avgCprsPerResource,
                                0,
                                -1,
                                -1,
                                0.0);
                    } finally {
                        writer.flush();
                        Transaction.resetMetrics();
                    }
                }
            }
        }

        System.out.println("Results exported to: " + outputFile);
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
                .maxTransactionResources(50)
                .useRandomResourceSelection(false)
                .semanticOverlap(0.10)
                .maxCprsPerResource(5)
                .avgCprsPerResource(2.0)
                .concurrencyModel(ConcurrencyModel.ACID)
                .simulationDurationSeconds(10000)
                .numberOfIoProcessors(15)
                .numberOfCpuProcessors(15)
                .numberOfReconcilerProcessors(0)
                .totalLocks(5000)
                .transactionSamplingSeed(12345L)
                .semanticOverlapSeed(67890L)
                .build();
    }

    public static CprSweep[] sweeps() {
        return new CprSweep[] {
                new CprSweep(
                        "fraction_with_cpr",
                        new VaryCprFraction(0.00, 0.05, 0.10, 0.20, 0.30, 0.50)),
                new CprSweep(
                        "max_cprs_per_resource",
                        new VaryMaxCprsPerResource(2, 3, 5, 8, 10)),
                new CprSweep(
                        "avg_cprs_per_resource",
                        new VaryAvgCprsPerResource(1.0, 1.5, 2.0, 3.0, 4.0))
        };
    }

    public record CprSweep(String name, ParameterVariation variation) {
        public String parameterName() {
            return variation.getParameterName();
        }
    }
}
