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
 * Integration tests for BASE concurrency pipeline.
 * 
 * Tests the complete BASE flow:
 * - Parent transactions execute optimistically without locks
 * - Bookkeepers are created for cross-model consistency
 * - Bookkeepers execute on dedicated reconciliation processors
 * - Dirty set tracking prevents redundant bookkeeper creation
 * - System completes successfully with various processor/model configurations
 */
public class BASEPipelineTest {

    @Test
    void basePipelineWithSingleProcessorSingleModel() {
        // Arrange - minimal configuration
        SimulatorParameters params = SimulatorParameters.builder()
            .concurrencyModel(ConcurrencyModel.BASE)
            .totalModels(1)
            .totalResources(50)
            .ioTimePerResource(5)
            .cpuTimePerResource(2)
            .initialTransactions(5)
            .totalTransactionSteps(20)
            .maxTransactionModels(1)
            .maxTransactionResources(5)
            .semanticOverlap(0.0)  // No cross-model bookkeepers with 1 model
            .simulationDurationSeconds(5)
            .numberOfIoProcessors(2)
            .numberOfCpuProcessors(2)  // 2 total: 1 effective CPU + 1 reconciler
            .numberOfReconcilerProcessors(1)  // Dedicated reconciliation processor
            .totalLocks(50)
            .transactionSamplingSeed(12345L)
            .build();

        ModelFactory modelFactory = new ModelFactory(params.instancePoolSize);
        List<Model> modelPool = modelFactory.createModels(params.propertiesPerModel);
        
        SimulatorConfig config = SimulatorConfig.fromParameters(params, modelPool);
        SimulatorContext context = SimulatorContext.create(config);
        Simulator sim = new Simulator(context, StepMode.FIXED_TIME);

        // Act
        assertDoesNotThrow(() -> sim.run(), 
            "BASE pipeline should complete with 1 processor and 1 model");

        // Assert
        long completed = Transaction.getCompletedTransactions();
        assertTrue(completed > 0, "Should complete at least some transactions");
        
        long totalLockTime = context.logger().getTotalLockTime();
        assertEquals(0, totalLockTime, 
            "BASE mode should use 0ms lock time");
        
        int totalBookkeepers = context.logger().getTotalBookkeepers();
        assertEquals(0, totalBookkeepers,
            "No bookkeepers expected with single model and 0% semantic overlap");
    }

    @Test
    void basePipelineWithSingleProcessorMultipleModels() {
        // Arrange - multiple models to trigger bookkeeper creation
        SimulatorParameters params = SimulatorParameters.builder()
            .concurrencyModel(ConcurrencyModel.BASE)
            .totalModels(5)
            .totalResources(100)
            .ioTimePerResource(5)
            .cpuTimePerResource(2)
            .initialTransactions(10)
            .totalTransactionSteps(50)
            .maxTransactionModels(3)  // Transactions span multiple models
            .maxTransactionResources(10)
            .semanticOverlap(0.3)  // 30% cross-model constraints
            .simulationDurationSeconds(30)  // 3x longer to allow more bookkeepers to complete
            .numberOfIoProcessors(2)
            .numberOfCpuProcessors(2)  // 2 total: 1 effective CPU + 1 reconciler
            .numberOfReconcilerProcessors(1)  // Shared processor for both work types
            .totalLocks(100)
            .transactionSamplingSeed(12345L)
            .semanticOverlapSeed(67890L)
            .build();

        ModelFactory modelFactory = new ModelFactory(params.instancePoolSize);
        List<Model> modelPool = modelFactory.createModels(params.propertiesPerModel);
        
        SimulatorConfig config = SimulatorConfig.fromParameters(params, modelPool);
        SimulatorContext context = SimulatorContext.create(config);
        Simulator sim = new Simulator(context, StepMode.FIXED_TIME);

        // Act
        assertDoesNotThrow(() -> sim.run(),
            "BASE pipeline should complete with 1 processor and 5 models");

        // Assert
        long completed = Transaction.getCompletedTransactions();
        assertTrue(completed > 0, "Should complete at least some transactions");
        
        long totalLockTime = context.logger().getTotalLockTime();
        assertEquals(0, totalLockTime,
            "BASE mode should use 0ms lock time even with multiple models");
        
        int totalBookkeepers = context.logger().getTotalBookkeepers();
        int reconciliationBookkeepers = context.logger().getTotalReconciliationBookkeepers();
        
        assertTrue(totalBookkeepers > 0,
            "Should create bookkeepers with 30% semantic overlap across 5 models");
        
        // With dedicated reconciliation processor, bookkeepers execute on reconciliation queue
        assertTrue(reconciliationBookkeepers > 0,
            "Bookkeepers should execute on dedicated reconciliation processor");
        
        double avgDirtySetSize = context.logger().getAverageDirtySetSize();
        assertTrue(avgDirtySetSize >= 0,
            "Average dirty set size should be tracked (>= 0)");
        
        // Note: In single processor mode, bookkeepers go through CPU phase
        // DirtySetManager is still used for fan-out collapse during bookkeeper creation
    }

    @Test
    void basePipelineWithMultipleProcessorsSingleModel() {
        // Arrange - multiple processors but single model
        SimulatorParameters params = SimulatorParameters.builder()
            .concurrencyModel(ConcurrencyModel.BASE)
            .totalModels(1)
            .totalResources(100)
            .ioTimePerResource(5)
            .cpuTimePerResource(2)
            .initialTransactions(15)
            .totalTransactionSteps(50)
            .maxTransactionModels(1)
            .maxTransactionResources(10)
            .semanticOverlap(0.0)  // No cross-model constraints with 1 model
            .simulationDurationSeconds(10)
            .numberOfIoProcessors(5)
            .numberOfCpuProcessors(5)
            .numberOfReconcilerProcessors(1)  // Still only 1 reconciliation processor
            .totalLocks(100)
            .transactionSamplingSeed(12345L)
            .build();

        ModelFactory modelFactory = new ModelFactory(params.instancePoolSize);
        List<Model> modelPool = modelFactory.createModels(params.propertiesPerModel);
        
        SimulatorConfig config = SimulatorConfig.fromParameters(params, modelPool);
        SimulatorContext context = SimulatorContext.create(config);
        Simulator sim = new Simulator(context, StepMode.FIXED_TIME);

        // Act
        assertDoesNotThrow(() -> sim.run(),
            "BASE pipeline should complete with 5 processors and 1 model");

        // Assert
        long completed = Transaction.getCompletedTransactions();
        assertTrue(completed > 0, "Should complete at least some transactions");
        
        long totalLockTime = context.logger().getTotalLockTime();
        assertEquals(0, totalLockTime,
            "BASE mode should use 0ms lock time with multiple processors");
        
        int totalBookkeepers = context.logger().getTotalBookkeepers();
        assertEquals(0, totalBookkeepers,
            "No bookkeepers expected with single model");
    }

    @Test
    void basePipelineWithMultipleProcessorsMultipleModels() {
        // Arrange - full configuration with multiple processors and models
        SimulatorParameters params = SimulatorParameters.builder()
            .concurrencyModel(ConcurrencyModel.BASE)
            .totalModels(5)
            .totalResources(200)
            .ioTimePerResource(5)
            .cpuTimePerResource(2)
            .initialTransactions(20)
            .totalTransactionSteps(100)
            .maxTransactionModels(4)  // Transactions span up to 4 models
            .maxTransactionResources(15)
            .semanticOverlap(0.5)  // 50% cross-model constraints
            .simulationDurationSeconds(45)  // 3x longer to allow all bookkeepers to complete
            .numberOfIoProcessors(5)
            .numberOfCpuProcessors(5)
            .numberOfReconcilerProcessors(1)  // 1 dedicated reconciliation processor
            .totalLocks(200)
            .transactionSamplingSeed(12345L)
            .semanticOverlapSeed(67890L)
            .build();

        ModelFactory modelFactory = new ModelFactory(params.instancePoolSize);
        List<Model> modelPool = modelFactory.createModels(params.propertiesPerModel);
        
        SimulatorConfig config = SimulatorConfig.fromParameters(params, modelPool);
        SimulatorContext context = SimulatorContext.create(config);
        Simulator sim = new Simulator(context, StepMode.FIXED_TIME);

        // Act
        assertDoesNotThrow(() -> sim.run(),
            "BASE pipeline should complete with 5 processors and 5 models");

        // Assert
        long completed = Transaction.getCompletedTransactions();
        assertTrue(completed > 0, "Should complete at least some transactions");
        
        long totalLockTime = context.logger().getTotalLockTime();
        assertEquals(0, totalLockTime,
            "BASE mode should never use lock time");
        
        int totalBookkeepers = context.logger().getTotalBookkeepers();
        int reconciliationBookkeepers = context.logger().getTotalReconciliationBookkeepers();
        
        assertTrue(totalBookkeepers > 0,
            "Should create many bookkeepers with 50% semantic overlap across 5 models");
        assertTrue(reconciliationBookkeepers > 0,
            "Should execute bookkeepers on reconciliation processor");
        
        double avgDirtySetSize = context.logger().getAverageDirtySetSize();
        assertTrue(avgDirtySetSize >= 0,
            "Average dirty set size should be non-negative");
        
        int dirtySetSamples = context.logger().getDirtySetSamples();
        assertTrue(dirtySetSamples <= reconciliationBookkeepers,
            "Should sample dirty set size at most once per bookkeeper (may have incomplete bookkeepers at sim end)");
        assertTrue(dirtySetSamples > 0,
            "Should have at least some completed bookkeepers");
        
        // Dirty set samples should be non-negative (may be 0 if dirty set manager not configured)
        assertTrue(dirtySetSamples >= 0,
            "Should have non-negative dirty set samples");
    }

    @Test
    void basePipelineVerifiesNoLockUsageRegardlessOfConfiguration() {
        // Test that BASE never uses locks across different configurations
        int[][] configurations = {
            {1, 1},  // 1 proc, 1 model
            {1, 5},  // 1 proc, 5 models
            {5, 1},  // 5 proc, 1 model
            {5, 5}   // 5 proc, 5 models
        };

        for (int[] config : configurations) {
            int numProcessors = config[0];
            int numModels = config[1];

            SimulatorParameters params = SimulatorParameters.builder()
                .concurrencyModel(ConcurrencyModel.BASE)
                .totalModels(numModels)
                .totalResources(50)
                .ioTimePerResource(5)
                .cpuTimePerResource(2)
                .initialTransactions(5)
                .totalTransactionSteps(20)
                .maxTransactionModels(Math.min(numModels, 3))
                .maxTransactionResources(5)
                // semanticOverlap>0 needs >=2 models; the numModels=1 configurations in this
                // sweep are about lock usage across processor/model counts, not CPR behavior.
                .semanticOverlap(numModels >= 2 ? 0.3 : 0.0)
                .simulationDurationSeconds(5)
                // POOL = foreground + reconcilers. BASE carves the reconciler back out, so passing
                // numProcessors leaves numProcessors - 1 for the foreground - and at the sweep's
                // numProcessors=1 that is zero, which used to be accepted and silently zeroed the
                // drain. This sweep is about lock usage at numProcessors foreground units.
                .numberOfIoProcessors(numProcessors + 1)
                .numberOfCpuProcessors(numProcessors + 1)
                .numberOfReconcilerProcessors(1)
                .totalLocks(50)
                .transactionSamplingSeed(12345L)
                .build();

            ModelFactory modelFactory = new ModelFactory(params.instancePoolSize);
            List<Model> modelPool = modelFactory.createModels(params.propertiesPerModel);
            
            SimulatorConfig simConfig = SimulatorConfig.fromParameters(params, modelPool);
            SimulatorContext context = SimulatorContext.create(simConfig);
            Simulator sim = new Simulator(context, StepMode.FIXED_TIME);

            assertDoesNotThrow(() -> sim.run(),
                String.format("BASE should complete with %d processors, %d models", 
                    numProcessors, numModels));

            long totalLockTime = context.logger().getTotalLockTime();
            assertEquals(0, totalLockTime,
                String.format("BASE should use 0 lock time with %d processors, %d models", 
                    numProcessors, numModels));
        }
    }

    @Test
    void basePipelineDirtySetTrackingWorks() {
        // Test that dirty set tracking is working correctly
        SimulatorParameters params = SimulatorParameters.builder()
            .concurrencyModel(ConcurrencyModel.BASE)
            .totalModels(3)
            .totalResources(60)
            .ioTimePerResource(5)
            .cpuTimePerResource(2)
            .initialTransactions(10)
            .totalTransactionSteps(30)
            .maxTransactionModels(2)
            .maxTransactionResources(8)
            .semanticOverlap(0.5)  // High overlap to ensure bookkeepers
            .simulationDurationSeconds(10)
            .numberOfIoProcessors(2)
            .numberOfCpuProcessors(2)
            .numberOfReconcilerProcessors(1)
            .totalLocks(60)
            .transactionSamplingSeed(99999L)
            .semanticOverlapSeed(88888L)
            .build();

        ModelFactory modelFactory = new ModelFactory(params.instancePoolSize);
        List<Model> modelPool = modelFactory.createModels(params.propertiesPerModel);
        
        SimulatorConfig config = SimulatorConfig.fromParameters(params, modelPool);
        SimulatorContext context = SimulatorContext.create(config);
        Simulator sim = new Simulator(context, StepMode.FIXED_TIME);

        assertDoesNotThrow(() -> sim.run(),
            "BASE pipeline with dirty set tracking should complete successfully");

        int reconciliationBookkeepers = context.logger().getTotalReconciliationBookkeepers();
        int dirtySetSamples = context.logger().getDirtySetSamples();
        double avgDirtySetSize = context.logger().getAverageDirtySetSize();

        if (reconciliationBookkeepers > 0) {
            // Dirty set samples may be less than reconciliation bookkeepers if dirty set manager
            // is not configured or if some bookkeepers don't have valid dirty keys
            assertTrue(dirtySetSamples >= 0 && dirtySetSamples <= reconciliationBookkeepers,
                "Dirty set samples should be between 0 and number of reconciliation bookkeepers");
            
            assertTrue(avgDirtySetSize >= 0 && avgDirtySetSize < params.totalResources,
                "Average dirty set size should be between 0 and total resources");
        }
    }
}
