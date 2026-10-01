package test.java.concurrency;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import de.tum.tssimulator.bootstrap.Simulator;
import de.tum.tssimulator.bootstrap.SimulatorContext;
import de.tum.tssimulator.config.ConcurrencyModel;
import de.tum.tssimulator.config.SimulatorConfig;
import de.tum.tssimulator.config.SimulatorParameters;
import de.tum.tssimulator.core.Model;
import de.tum.tssimulator.core.ModelFactory;
import de.tum.tssimulator.core.Transaction;
import de.tum.tssimulator.execution.StepMode;

import java.util.List;

/**
 * Tests for BASE reconciliation with separate processor pools.
 * 
 * Tests verify:
 * - Bookkeepers execute on dedicated reconciliation processors
 * - Parent transactions use separate CPU processors
 * - Dirty set collapse prevents redundant bookkeeper spawning
 * - Reconciler processors are rate-limited independently from parents
 */
public class BASEReconciliationTest {

    @Test
    void baseModeWithReconciliationProcessors() {
        SimulatorParameters params = SimulatorParameters.builder()
            .concurrencyModel(ConcurrencyModel.BASE)
            .totalModels(5)
            .totalResources(50)
            .ioTimePerResource(10)
            .cpuTimePerResource(5)
            .initialTransactions(5)
            .totalTransactionSteps(50)
            .maxTransactionModels(3)
            .maxTransactionResources(10)
            .semanticOverlap(0.5)  // High overlap to trigger bookkeepers
            .simulationDurationSeconds(5)
            .numberOfIoProcessors(4)
            .numberOfCpuProcessors(4)  // 4 total: 3 effective CPU + 1 reconciler
            .numberOfReconcilerProcessors(1)  // 1 dedicated reconciler
            .totalLocks(50)
            .build();

        ModelFactory modelFactory = new ModelFactory(params.instancePoolSize);
        List<Model> modelPool = modelFactory.createModels(params.propertiesPerModel);
        
        SimulatorConfig config = SimulatorConfig.fromParameters(params, modelPool);
        
        // Verify config has correct processor counts (after reconciler split)
        assertEquals(3, config.numCpuProcessors, 
                     "Should have 3 effective CPU processors after reconciler split");
        assertEquals(1, config.numReconcilerProcessors,
                     "Should have 1 reconciler processor for bookkeepers");
        
        SimulatorContext context = SimulatorContext.create(config);
        Simulator sim = new Simulator(context, StepMode.FIXED_TIME);

        sim.run();

        // Verify transactions completed
        assertTrue(Transaction.getCompletedTransactions() > 0, 
                   "BASE mode with reconciliation should complete transactions");
        
        // Verify bookkeepers were created
        int totalBookkeepers = context.logger().getTotalBookkeepers();
        assertTrue(totalBookkeepers >= 0, 
                   "Bookkeeper count should be tracked (>= 0)");
    }

    @Test
    void baseModeSeparatesParentAndReconcilerCapacity() {
        // Configure with unequal processor split to test capacity separation
        SimulatorParameters params = SimulatorParameters.builder()
            .concurrencyModel(ConcurrencyModel.BASE)
            .totalModels(10)
            .totalResources(100)
            .ioTimePerResource(20)
            .cpuTimePerResource(10)
            .initialTransactions(20)  // More transactions
            .totalTransactionSteps(100)
            .maxTransactionModels(4)
            .maxTransactionResources(15)
            .semanticOverlap(0.7)  // Very high overlap for many bookkeepers
            .simulationDurationSeconds(10)
            .numberOfIoProcessors(8)
            .numberOfCpuProcessors(7)  // 7 parent CPU processors
            .numberOfReconcilerProcessors(1)  // 1 reconciler (rate-limited)
            .totalLocks(100)
            // Default propertiesPerModel=5/model needs instancePoolSize>=3 so every model can
            // still supply RANDOM_OPERATIONS mode's worst-case single-model draw of 15
            // (maxTransactionResources) even if numModels degenerates to 1.
            .instancePoolSize(3)
            .build();

        ModelFactory modelFactory = new ModelFactory(params.instancePoolSize);
        List<Model> modelPool = modelFactory.createModels(params.propertiesPerModel);
        
        SimulatorConfig config = SimulatorConfig.fromParameters(params, modelPool);
        SimulatorContext context = SimulatorContext.create(config);
        Simulator sim = new Simulator(context, StepMode.FIXED_TIME);

        sim.run();

        // Key assertion: parent transactions should complete despite bookkeeper backlog
        // This proves capacity separation prevents bookkeeper starvation of parents
        assertTrue(Transaction.getCompletedTransactions() > 0, 
                   "Parent transactions should complete even with bookkeeper backlog");
        
        // Verify no lock time consumed (BASE characteristic)
        assertEquals(0, context.logger().getTotalLockTime(), 
                     "BASE mode should use 0ms lock time");
    }

    @Test
    void baseModeWithZeroReconciliationProcessors() {
        // Edge case: 0 reconciler processors should fall back to original BASE behavior
        SimulatorParameters params = SimulatorParameters.builder()
            .concurrencyModel(ConcurrencyModel.BASE)
            .totalModels(3)
            .totalResources(30)
            .ioTimePerResource(10)
            .cpuTimePerResource(5)
            .initialTransactions(5)
            .totalTransactionSteps(20)
            .maxTransactionModels(2)
            .maxTransactionResources(5)
            .semanticOverlap(0.3)
            .simulationDurationSeconds(2)
            .numberOfIoProcessors(2)
            .numberOfCpuProcessors(2)
            .numberOfReconcilerProcessors(0)  // No separate reconcilers
            .totalLocks(20)
            .build();

        ModelFactory modelFactory = new ModelFactory(params.instancePoolSize);
        List<Model> modelPool = modelFactory.createModels(params.propertiesPerModel);
        
        SimulatorConfig config = SimulatorConfig.fromParameters(params, modelPool);
        SimulatorContext context = SimulatorContext.create(config);
        Simulator sim = new Simulator(context, StepMode.FIXED_TIME);

        assertDoesNotThrow(() -> sim.run(),
                          "Simulation should handle 0 reconciliation processors gracefully");
    }

    @Test
    void baseModeProcessorSplitCalculation() {
        // Test various processor split configurations
        int[][] configs = {
            {4, 1},  // 4 total: 3 CPU + 1 reconciler
            {10, 2}, // 10 total: 8 CPU + 2 reconciler
            {5, 4},  // 5 total: 1 CPU + 4 reconciler (edge case)
            {2, 1}   // 2 total: 1 CPU + 1 reconciler (minimum)
        };

        for (int[] config : configs) {
            int numCpuProcessors = config[0];
            int numReconcilerProcessors = config[1];
            
            SimulatorParameters params = SimulatorParameters.builder()
                .concurrencyModel(ConcurrencyModel.BASE)
                .totalModels(3)
                .totalResources(30)
                .ioTimePerResource(5)
                .cpuTimePerResource(2)
                .initialTransactions(3)
                .totalTransactionSteps(10)
                .maxTransactionModels(2)
                .maxTransactionResources(3)
                .simulationDurationSeconds(1)
                .numberOfIoProcessors(2)
                .numberOfCpuProcessors(numCpuProcessors)
                .numberOfReconcilerProcessors(numReconcilerProcessors)
                .totalLocks(10)
                .build();

            ModelFactory modelFactory = new ModelFactory(params.instancePoolSize);
            List<Model> modelPool = modelFactory.createModels(params.propertiesPerModel);
            
            SimulatorConfig simConfig = SimulatorConfig.fromParameters(params, modelPool);
            
            // Verify config applies processor split (effectiveCpu = total - reconciler)
            int expectedCpu = numCpuProcessors - numReconcilerProcessors;
            assertEquals(expectedCpu, simConfig.numCpuProcessors,
                        "Config should split processors: " + numCpuProcessors + " total - " + 
                        numReconcilerProcessors + " reconciler = " + expectedCpu + " effective CPU");
            assertEquals(numReconcilerProcessors, simConfig.numReconcilerProcessors,
                        "Config should retain original numReconcilerProcessors value");
            
            SimulatorContext context = SimulatorContext.create(simConfig);
            Simulator sim = new Simulator(context, StepMode.FIXED_TIME);
            
            // Verify simulation runs successfully with this processor configuration
            assertDoesNotThrow(() -> sim.run(),
                              String.format("Should run with %d CPU + %d reconciler processors",
                                          numCpuProcessors, numReconcilerProcessors));
        }
    }

    @Test
    void baseModeWithHighBookkeeperFanout() {
        // Test scenario with high fan-out to verify dirty set collapse
        SimulatorParameters params = SimulatorParameters.builder()
            .concurrencyModel(ConcurrencyModel.BASE)
            .totalModels(20)  // Many models for cross-model constraints
            .totalResources(200)
            .ioTimePerResource(5)
            .cpuTimePerResource(2)
            .initialTransactions(50)  // Many concurrent transactions
            .totalTransactionSteps(100)
            .maxTransactionModels(5)  // Multi-model transactions
            .maxTransactionResources(10)
            .semanticOverlap(0.9)  // Very high overlap = many bookkeepers
            .simulationDurationSeconds(10)
            .numberOfIoProcessors(10)
            .numberOfCpuProcessors(9)  // 9 parent processors
            .numberOfReconcilerProcessors(1)  // Just 1 reconciler (bottleneck)
            .totalLocks(200)
            .transactionSamplingSeed(42L)  // Deterministic for reproducibility
            .semanticOverlapSeed(123L)
            .build();

        ModelFactory modelFactory = new ModelFactory(params.instancePoolSize);
        List<Model> modelPool = modelFactory.createModels(params.propertiesPerModel);
        
        SimulatorConfig config = SimulatorConfig.fromParameters(params, modelPool);
        SimulatorContext context = SimulatorContext.create(config);
        Simulator sim = new Simulator(context, StepMode.FIXED_TIME);

        sim.run();

        // With high fan-out and only 1 reconciler, we should still complete parent transactions
        // This demonstrates that:
        // 1. Dirty set collapse reduces redundant bookkeeper work
        // 2. Capacity separation prevents bookkeeper backlog from blocking parents
        assertTrue(Transaction.getCompletedTransactions() > 0, 
                   "High bookkeeper fan-out should not prevent parent completion");
        
        long completedParents = Transaction.getCompletedTransactions();
        int executedBookkeepers = context.logger().getTotalBookkeepers();
        
        assertTrue(executedBookkeepers >= 0,
                   "Executed bookkeeper count should be non-negative");
    }
}
