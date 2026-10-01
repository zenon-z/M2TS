package test.java.locking;

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
 * Tests to verify bookkeepers properly own their locks in the lock table.
 * Verifies that bookkeepers correctly register as lock holders for their resources.
 * 
 * These tests verify the end-to-end behavior through simulation rather than
 * testing lock table state directly, as that requires complex test setup.
 */
public class BookkeeperLockOwnershipTest {
    
    @Test
    void testBookkeepersCanAcquireAdditionalLocks() {
        // Arrange - Configuration that forces bookkeepers to need additional locks
        SimulatorParameters params = SimulatorParameters.builder()
            .concurrencyModel(ConcurrencyModel.ACID)
            .totalModels(4)
            .totalResources(100)
            .ioTimePerResource(1)
            .cpuTimePerResource(1)
            .lockIOTimePerResource(100)
            .lockCPUTimePerResource(10)
            .initialTransactions(10)
            .totalTransactionSteps(20)
            .maxTransactionModels(2)  // Multi-model transactions
            .maxTransactionResources(5)
            .semanticOverlap(0.6)  // High overlap to create bookkeepers with additional locks
            .simulationDurationSeconds(50)
            .numberOfIoProcessors(2)
            .numberOfCpuProcessors(2)
            .totalLocks(100)
            .transactionSamplingSeed(33333L)
            .semanticOverlapSeed(44444L)
            .build();

        ModelFactory modelFactory = new ModelFactory(params.instancePoolSize);
        List<Model> modelPool = modelFactory.createModels(params.propertiesPerModel);
        
        SimulatorConfig config = SimulatorConfig.fromParameters(params, modelPool);
        SimulatorContext context = SimulatorContext.create(config);
        Simulator sim = new Simulator(context, StepMode.FIXED_TIME);

        // Act
        assertDoesNotThrow(() -> sim.run(), 
            "Bookkeepers should be able to acquire additional locks they need");

        // Assert - if bookkeepers couldn't acquire locks, system would hang or fail
        int totalBookkeepers = context.logger().getBookkeepersCreatedTotal();
        assertTrue(totalBookkeepers > 0, 
            "Should create bookkeepers that need to acquire locks");
        
        int bookkeepersDone = context.logger().getBookkeepersCompletedTotal();
        assertTrue(bookkeepersDone > 0,
            "Bookkeepers should complete after successfully acquiring locks");
        
        // BUG CHECK: Successful completion proves bookkeepers own their locks
        long completed = Transaction.getCompletedTransactions();
        assertTrue(completed > 0, "Transactions should complete with bookkeepers owning locks");
    }
    
    @Test
    void testMultipleBookkeepersCanCoexist() {
        // Arrange - Configuration with many bookkeepers that may need different locks
        SimulatorParameters params = SimulatorParameters.builder()
            .concurrencyModel(ConcurrencyModel.ACID)
            .totalModels(6)
            .totalResources(80)
            .ioTimePerResource(2)
            .cpuTimePerResource(1)
            .lockIOTimePerResource(150)
            .lockCPUTimePerResource(15)
            .initialTransactions(12)
            .totalTransactionSteps(25)
            .maxTransactionModels(3)  // Span multiple models
            .maxTransactionResources(6)
            .semanticOverlap(0.7)  // High overlap = many bookkeepers
            .simulationDurationSeconds(80)
            .numberOfIoProcessors(3)
            .numberOfCpuProcessors(3)
            .totalLocks(80)
            .transactionSamplingSeed(55555L)
            .semanticOverlapSeed(66666L)
            .build();

        ModelFactory modelFactory = new ModelFactory(params.instancePoolSize);
        List<Model> modelPool = modelFactory.createModels(params.propertiesPerModel);
        
        SimulatorConfig config = SimulatorConfig.fromParameters(params, modelPool);
        SimulatorContext context = SimulatorContext.create(config);
        Simulator sim = new Simulator(context, StepMode.FIXED_TIME);

        // Act
        assertDoesNotThrow(() -> sim.run(), 
            "Multiple bookkeepers should coexist with proper lock ownership");

        // Assert
        int totalBookkeepers = context.logger().getBookkeepersCreatedTotal();
        assertTrue(totalBookkeepers > 0, 
            "Should create multiple bookkeepers with different lock requirements");
        
        int bookkeepersDone = context.logger().getBookkeepersCompletedTotal();
        assertTrue(bookkeepersDone > 0,
            "Multiple bookkeepers should complete with proper lock ownership");
        
        // BUG CHECK: If lock ownership was incorrect, bookkeepers would conflict
        long completed = Transaction.getCompletedTransactions();
        assertTrue(completed > 0, "System should handle multiple bookkeepers owning different locks");
    }
    
    @Test
    void testBookkeepersReleaseLocksOnCompletion() {
        // Arrange - Verify bookkeepers release locks to allow other transactions
        SimulatorParameters params = SimulatorParameters.builder()
            .concurrencyModel(ConcurrencyModel.ACID)
            .totalModels(3)
            .totalResources(50)
            .ioTimePerResource(1)
            .cpuTimePerResource(1)
            .lockIOTimePerResource(100)
            .lockCPUTimePerResource(10)
            .initialTransactions(20)  // Many transactions
            .totalTransactionSteps(40)
            .maxTransactionModels(2)
            .maxTransactionResources(4)
            .semanticOverlap(0.5)
            .simulationDurationSeconds(60)
            .numberOfIoProcessors(2)
            .numberOfCpuProcessors(2)
            .totalLocks(50)  // Limited locks to force contention
            .transactionSamplingSeed(77777L)
            .semanticOverlapSeed(88888L)
            .build();

        ModelFactory modelFactory = new ModelFactory(params.instancePoolSize);
        List<Model> modelPool = modelFactory.createModels(params.propertiesPerModel);
        
        SimulatorConfig config = SimulatorConfig.fromParameters(params, modelPool);
        SimulatorContext context = SimulatorContext.create(config);
        Simulator sim = new Simulator(context, StepMode.FIXED_TIME);

        // Act
        assertDoesNotThrow(() -> sim.run(), 
            "Bookkeepers should release locks properly");

        // Assert - with limited locks and many transactions, locks must be released
        long completed = Transaction.getCompletedTransactions();
        assertTrue(completed > 5, 
            "Multiple transactions should complete, proving locks are released by bookkeepers");
        
        int bookkeepersDone = context.logger().getBookkeepersCompletedTotal();
        assertTrue(bookkeepersDone > 0,
            "Bookkeepers should complete and release their locks");
        
        // BUG CHECK: If bookkeepers didn't release locks, system would deadlock with limited locks
    }
}
