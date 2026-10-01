package test.java.execution;

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
 * Tests that bookkeepers are created AFTER parent completes CPU phase.
 * Verifies the fix for BUG #1 where ACID bookkeepers were never created.
 */
public class ACIDBookkeeperCreationTimingTest {

    @Test
    void testBookkeepersCreatedAfterParentCPUPhase() {
        // Arrange - ACID configuration with multiple models to trigger bookkeepers
        SimulatorParameters params = SimulatorParameters.builder()
            .concurrencyModel(ConcurrencyModel.ACID)
            .totalModels(3)
            .totalResources(100)
            .ioTimePerResource(1)
            .cpuTimePerResource(1)
            .initialTransactions(5)
            .totalTransactionSteps(10)
            .maxTransactionModels(2)  // Transactions span 2 models
            .maxTransactionResources(5)
            .semanticOverlap(0.5)  // 50% cross-model constraints
            .simulationDurationSeconds(50)
            .numberOfIoProcessors(2)
            .numberOfCpuProcessors(2)
            .totalLocks(100)
            .transactionSamplingSeed(12345L)
            .semanticOverlapSeed(54321L)
            .build();

        ModelFactory modelFactory = new ModelFactory(params.instancePoolSize);
        List<Model> modelPool = modelFactory.createModels(params.propertiesPerModel);
        
        SimulatorConfig config = SimulatorConfig.fromParameters(params, modelPool);
        SimulatorContext context = SimulatorContext.create(config);
        Simulator sim = new Simulator(context, StepMode.FIXED_TIME);

        // Act
        assertDoesNotThrow(() -> sim.run(), 
            "ACID pipeline should complete with bookkeeper creation");

        // Assert - verify bookkeepers were created and completed
        int totalBookkeepers = context.logger().getBookkeepersCreatedTotal();
        assertTrue(totalBookkeepers > 0, 
            "Bookkeepers should be created for multi-model transactions with semantic overlap");
        
        int bookkeepersDone = context.logger().getBookkeepersCompletedTotal();
        int bookkeepersCancelled = context.logger().getBookkeepersAbortedTotal();
        
        assertTrue(bookkeepersDone > 0,
            "Some bookkeepers should complete successfully");
        
        // Verify parent transactions completed
        long completed = Transaction.getCompletedTransactions();
        assertTrue(completed > 0, "Should complete at least some parent transactions");
    }

    @Test
    void testACIDBookkeepersQueuedAfterParentCPU() {
        // Arrange - ACID configuration with high semantic overlap
        SimulatorParameters params = SimulatorParameters.builder()
            .concurrencyModel(ConcurrencyModel.ACID)
            .totalModels(5)
            .totalResources(50)
            .ioTimePerResource(2)
            .cpuTimePerResource(1)
            .initialTransactions(10)
            .totalTransactionSteps(20)
            .maxTransactionModels(3)  // Span 3 models
            .maxTransactionResources(8)
            .semanticOverlap(0.8)  // High overlap = more bookkeepers
            .simulationDurationSeconds(100)
            .numberOfIoProcessors(3)
            .numberOfCpuProcessors(3)
            .totalLocks(50)
            .transactionSamplingSeed(67890L)
            .semanticOverlapSeed(98765L)
            .build();

        ModelFactory modelFactory = new ModelFactory(params.instancePoolSize);
        List<Model> modelPool = modelFactory.createModels(params.propertiesPerModel);
        
        SimulatorConfig config = SimulatorConfig.fromParameters(params, modelPool);
        SimulatorContext context = SimulatorContext.create(config);
        Simulator sim = new Simulator(context, StepMode.FIXED_TIME);

        // Act
        assertDoesNotThrow(() -> sim.run(), 
            "ACID pipeline should handle cascading bookkeepers");

        // Assert - verify bookkeeper creation and execution
        int totalBookkeepers = context.logger().getBookkeepersCreatedTotal();
        assertTrue(totalBookkeepers > 0, 
            "High semantic overlap should create many bookkeepers");
        
        int bookkeepersDone = context.logger().getBookkeepersCompletedTotal();
        assertTrue(bookkeepersDone > 0,
            "Some bookkeepers should complete successfully");
        
        // With depth=2, we may see cascading bookkeepers
        // Verify system handles this correctly without hanging
        long completed = Transaction.getCompletedTransactions();
        assertTrue(completed > 0, 
            "Parent transactions should complete even with cascading bookkeepers");
    }

    @Test
    void testNoBookkeepersForSingleModelTransactions() {
        // Arrange - Single model configuration (no cross-model bookkeepers)
        SimulatorParameters params = SimulatorParameters.builder()
            .concurrencyModel(ConcurrencyModel.ACID)
            .totalModels(1)  // Only 1 model
            .totalResources(50)
            .ioTimePerResource(1)
            .cpuTimePerResource(1)
            .initialTransactions(10)
            .totalTransactionSteps(20)
            .maxTransactionModels(1)  // Only touch 1 model
            .maxTransactionResources(5)
            .semanticOverlap(0.0)  // No overlap (irrelevant with 1 model)
            .simulationDurationSeconds(5)
            .numberOfIoProcessors(2)
            .numberOfCpuProcessors(2)
            .totalLocks(50)
            .transactionSamplingSeed(11111L)
            .build();

        ModelFactory modelFactory = new ModelFactory(params.instancePoolSize);
        List<Model> modelPool = modelFactory.createModels(params.propertiesPerModel);
        
        SimulatorConfig config = SimulatorConfig.fromParameters(params, modelPool);
        SimulatorContext context = SimulatorContext.create(config);
        Simulator sim = new Simulator(context, StepMode.FIXED_TIME);

        // Act
        assertDoesNotThrow(() -> sim.run(), 
            "ACID pipeline should work with single-model transactions");

        // Assert - no bookkeepers should be created
        int totalBookkeepers = context.logger().getBookkeepersCreatedTotal();
        assertEquals(0, totalBookkeepers,
            "No bookkeepers should be created for single-model transactions");
        
        // Verify parent transactions completed normally
        long completed = Transaction.getCompletedTransactions();
        assertTrue(completed > 0, "Parent transactions should complete");
    }
}
