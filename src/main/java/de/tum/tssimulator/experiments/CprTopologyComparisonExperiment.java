package de.tum.tssimulator.experiments;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import de.tum.tssimulator.bookkeeping.CprTopologyMode;
import de.tum.tssimulator.bootstrap.SimulationWatchdogException;
import de.tum.tssimulator.config.SimulatorParameters;
import de.tum.tssimulator.core.Transaction;
import de.tum.tssimulator.experiments.support.ExperimentOutputPaths;
import de.tum.tssimulator.experiments.support.SeedSweepRunner;

/**
 * Compare topology effects across three CPR fan-out settings.
 * Each fan-out setting gets:
 * - one fixed uniform line profile (avg=1, max=1)
 * - one uniform broader-fanout profile
 * - one hotspot profile
 * - one layered profile
 */
public class CprTopologyComparisonExperiment {
    private static final String OUTPUT_FILE = "cpr_topology_comparison.csv";
    private static final String FANOUT_FILTER_PROPERTY = "ts.cprTopologyComparison.setting";
    private static final String OUTPUT_FILE_PROPERTY = "ts.cprTopologyComparison.outputFile";
    private static final String TRANSACTION_SEED_PROPERTY = "ts.cprTopologyComparison.transactionSeed";
    private static final String OVERLAP_SEED_PROPERTY = "ts.cprTopologyComparison.overlapSeed";
    private static final double[] SEMANTIC_OVERLAPS = {0.00, 0.05, 0.10, 0.20, 0.30, 0.50};
    private static final FanoutSetting[] FANOUT_SETTINGS = {
            new FanoutSetting("avg1_max3", 1.0, 3),
            new FanoutSetting("avg2_max5", 2.0, 5),
            new FanoutSetting("avg3_max6", 3.0, 6),
            new FanoutSetting("avg4_max10", 4.0, 10),
            new FanoutSetting("avg5_max10", 5.0, 10)
    };
    private static final Profile FIXED_LINE = new Profile("uniform_line", CprTopologyMode.UNIFORM, 1, 1.0);
    private static final String[] PROFILE_NAMES = {"uniform_line", "uniform_non_line", "hotspot", "ring"};

    public static void main(String[] args) throws IOException {
        SimulatorParameters base = applySeedOverrides(CprImpactExperimentSuite.baseParameters());
        SimulationRunner runner = new SimulationRunner();
        String outputFile = ExperimentOutputPaths.resultsFile(
                System.getProperty(OUTPUT_FILE_PROPERTY, OUTPUT_FILE));
        FanoutSetting[] selectedSettings = selectedFanoutSettings();
        boolean filteredRun = selectedSettings.length != FANOUT_SETTINGS.length;
        Path outputPath = Path.of(outputFile);

        SimulatorParameters baselineParams = baselineParameters(base);
        ExperimentResult baseline = runner.run("cpr_topology_comparison_baseline", baselineParams);
        double baselineThroughput = baseline.throughput;
        Transaction.resetMetrics();

        int totalRuns = selectedSettings.length * profileCountPerSetting() * SEMANTIC_OVERLAPS.length;
        int currentRun = 0;
        ArrayList<String> existingRows = filteredRun ? loadRowsExcludingSelectedSettings(outputPath, selectedSettings)
                : new ArrayList<>();

        try (PrintWriter writer = new PrintWriter(new FileWriter(outputFile))) {
            writer.println("fanout_setting,profile,topology_mode,semantic_overlap,max_cprs_per_resource,avg_cprs_per_resource,"
                    + "completed,throughput_tps,baseline_tps,relative_throughput,bookkeepers_created,"
                    + "bookkeepers_completed,bookkeepers_unfinished,groups_started,mean_parent_resource_refs,"
                    + "mean_cpr_resource_refs,status");
            for (String row : existingRows) {
                writer.println(row);
            }

            for (FanoutSetting setting : selectedSettings) {
                Profile[] profiles = profilesFor(setting);
                for (Profile profile : profiles) {
                    for (double overlap : SEMANTIC_OVERLAPS) {
                        currentRun++;
                        SimulatorParameters params = applyProfile(base, profile, overlap);
                        System.out.printf(Locale.US,
                                "[%d/%d] CPR topology comparison: fanout=%s profile=%s overlap=%.2f%n",
                                currentRun, totalRuns, setting.label(), profile.name(), overlap);

                        String runName = String.format(Locale.US, "cpr_topology_%s_%s_overlap_%.2f",
                                setting.label(), profile.name(), overlap);
                        try {
                            ExperimentResult result = runner.run(runName, params);
                            writeRow(writer, setting, profile, params, result, baselineThroughput, "OK");
                        } catch (SimulationWatchdogException e) {
                            System.out.printf(Locale.US,
                                    "  Watchdog failure recorded for fanout=%s profile=%s overlap=%.2f: %s%n",
                                    setting.label(), profile.name(), overlap, e.getMessage());
                            writeWatchdogRow(writer, setting, profile, params, baselineThroughput);
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

    public static double[] semanticOverlaps() {
        return SEMANTIC_OVERLAPS.clone();
    }

    public static FanoutSetting[] fanoutSettings() {
        return FANOUT_SETTINGS.clone();
    }

    public static String[] profileNames() {
        return PROFILE_NAMES.clone();
    }

    public static int uniqueRunCount() {
        return FANOUT_SETTINGS.length * profileCountPerSetting() * SEMANTIC_OVERLAPS.length;
    }

    private static SimulatorParameters applySeedOverrides(SimulatorParameters base) {
        Long transactionSeed = Long.getLong(TRANSACTION_SEED_PROPERTY);
        Long overlapSeed = Long.getLong(OVERLAP_SEED_PROPERTY);
        if (transactionSeed == null && overlapSeed == null) {
            return base;
        }
        return copyBuilder(base)
                .transactionSamplingSeed(transactionSeed != null ? transactionSeed : base.transactionSamplingSeed)
                .semanticOverlapSeed(overlapSeed != null ? overlapSeed : base.semanticOverlapSeed)
                .build();
    }

    private static FanoutSetting[] selectedFanoutSettings() {
        String filter = System.getProperty(FANOUT_FILTER_PROPERTY);
        if (filter == null || filter.isBlank()) {
            return FANOUT_SETTINGS.clone();
        }

        Optional<FanoutSetting> match = Arrays.stream(FANOUT_SETTINGS)
                .filter(setting -> setting.label().equals(filter))
                .findFirst();
        if (match.isEmpty()) {
            throw new IllegalArgumentException("Unknown fanout setting for property " + FANOUT_FILTER_PROPERTY + ": "
                    + filter);
        }
        return new FanoutSetting[] {match.get()};
    }

    private static ArrayList<String> loadRowsExcludingSelectedSettings(Path outputPath, FanoutSetting[] selectedSettings)
            throws IOException {
        ArrayList<String> rows = new ArrayList<>();
        if (!Files.exists(outputPath)) {
            return rows;
        }
        Set<String> labelsToReplace = Arrays.stream(selectedSettings)
                .map(FanoutSetting::label)
                .collect(Collectors.toSet());
        for (String line : Files.readAllLines(outputPath)) {
            if (line.isBlank() || line.startsWith("fanout_setting,")) {
                continue;
            }
            String label = line.substring(0, line.indexOf(','));
            if (!labelsToReplace.contains(label)) {
                rows.add(line);
            }
        }
        return rows;
    }

    private static int profileCountPerSetting() {
        return PROFILE_NAMES.length;
    }

    private static Profile[] profilesFor(FanoutSetting setting) {
        return new Profile[] {
                FIXED_LINE,
                new Profile("uniform_non_line", CprTopologyMode.UNIFORM, setting.maxCprsPerResource(),
                        setting.avgCprsPerResource()),
                new Profile("hotspot", CprTopologyMode.HOTSPOT, setting.maxCprsPerResource(),
                        setting.avgCprsPerResource()),
                new Profile("ring", CprTopologyMode.RING, setting.maxCprsPerResource(),
                        setting.avgCprsPerResource())
        };
    }

    private static SimulatorParameters baselineParameters(SimulatorParameters base) {
        return SimulatorParameters.builder()
                .totalModels(1)
                .totalResources(base.totalResources)
                .ioTimePerResource(base.ioTimePerResource)
                .cpuTimePerResource(base.cpuTimePerResource)
                .lockIOTimePerResource(base.lockIOTimePerResource)
                .lockCPUTimePerResource(base.lockCPUTimePerResource)
                .initialTransactions(base.initialTransactions)
                .totalTransactionSteps(base.totalTransactionSteps)
                .maxTransactionModels(1)
                .maxTransactionResources(base.maxTransactionResources)
                .useConstantTransactionSize(base.useConstantTransactionSize)
                .constantTransactionSize(base.constantTransactionSize)
                .useRandomResourceSelection(base.useRandomResourceSelection)
                .semanticOverlap(0.0)
                .maxCprsPerResource(1)
                .avgCprsPerResource(1.0)
                .cprTopologyMode(CprTopologyMode.UNIFORM)
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

    private static SimulatorParameters applyProfile(SimulatorParameters base, Profile profile, double overlap) {
        return copyBuilder(base)
                .semanticOverlap(overlap)
                .maxCprsPerResource(profile.maxCprsPerResource())
                .avgCprsPerResource(profile.avgCprsPerResource())
                .cprTopologyMode(profile.topologyMode())
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

    private static void writeRow(PrintWriter writer, FanoutSetting setting, Profile profile, SimulatorParameters params,
            ExperimentResult result, double baselineThroughput, String status) {
        double relative = baselineThroughput == 0.0 ? 0.0 : result.throughput / baselineThroughput;
        int unfinishedBookkeepers = Math.max(0,
                result.bookkeepersCreatedTotal - result.bookkeepersCompletedTotal - result.bookkeepersAbortedTotal);
        writer.printf(Locale.US, "%s,%s,%s,%.4f,%d,%.4f,%d,%.6f,%.6f,%.6f,%d,%d,%d,%d,%.6f,%.6f,%s%n",
                setting.label(),
                profile.name(),
                profile.topologyMode(),
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

    private static void writeWatchdogRow(PrintWriter writer, FanoutSetting setting, Profile profile,
            SimulatorParameters params, double baselineThroughput) {
        writer.printf(Locale.US, "%s,%s,%s,%.4f,%d,%.4f,%d,%.6f,%.6f,%.6f,%d,%d,%d,%d,%.6f,%.6f,WATCHDOG%n",
                setting.label(),
                profile.name(),
                profile.topologyMode(),
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

    public record Profile(String name, CprTopologyMode topologyMode, int maxCprsPerResource, double avgCprsPerResource) {
    }

    public record FanoutSetting(String label, double avgCprsPerResource, int maxCprsPerResource) {
    }
}
