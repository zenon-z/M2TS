package test.java.util;

import java.util.List;

import de.tum.tssimulator.config.SimulatorConfig;
import de.tum.tssimulator.config.SimulatorParameters;
import de.tum.tssimulator.core.Model;

/**
 * Test utility for creating minimal SimulatorConfig instances.
 * Provides backwards-compatible helper methods for tests.
 */
public class TestConfigFactory {
    
    /**
     * Create a minimal SimulatorConfig for testing with all defaults.
     */
    public static SimulatorConfig createMinimal() {
        return SimulatorConfig.fromParameters(
            SimulatorParameters.builder()
                .totalModels(1)
                .totalResources(1)
                .ioTimePerResource(0)
                .cpuTimePerResource(0)
                .initialTransactions(0)
                .totalTransactionSteps(0)
                .simulationDurationSeconds(1)
                .maxTransactionModels(0)
                .maxTransactionResources(0)
                .lockIOTimePerResource(0)
                .lockCPUTimePerResource(0)
                .numberOfIoProcessors(1)
                .numberOfCpuProcessors(1)
                .totalLocks(1)
                .build(),
            List.of()
        );
    }
    
    /**
     * Create a SimulatorConfig with specific values for backwards compatibility.
     * Maps to the old 15-parameter constructor pattern.
     */
    public static SimulatorConfig create(
            int totalModels, int totalResources,
            long ioTimePerResource, long cpuTimePerResource,
            int initialTransactionCount, int totalTransactionSteps, int simulationDurationSeconds,
            List<Model> modelPool,
            int maxTransactionModels, int maxTransactionResources,
            long lockIOTime, long lockCPUTimePerResource,
            int numIoProcessors, int numCpuProcessors,
            int totalLocks) {
        
        return new SimulatorConfig(
            totalModels, totalResources,
            ioTimePerResource, cpuTimePerResource,
            initialTransactionCount, totalTransactionSteps, simulationDurationSeconds,
            modelPool,
            maxTransactionModels, maxTransactionResources,
            lockIOTime, lockCPUTimePerResource,
            numIoProcessors, numCpuProcessors,
            totalLocks
        );
    }
}
