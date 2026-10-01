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
 * Tests for BASE (Basically Available, Soft state, Eventual consistency) concurrency strategy.
 * 
 * BASE should:
 * - Process all transactions immediately (no blocking)
 * - Skip LOCK phase entirely
 * - Not consume lock time (optimistic approach)
 * - Support Last-Write-Wins reconciliation
 */
public class BASEStrategyTest {

    @Test
    void baseModeConfiguresCorrectly() {
        SimulatorParameters params = SimulatorParameters.builder()
            .concurrencyModel(ConcurrencyModel.BASE)
            .totalModels(10)
            .totalResources(100)
            .ioTimePerResource(10)
            .cpuTimePerResource(5)
            .initialTransactions(5)
            .totalTransactionSteps(100)
            .maxTransactionModels(3)
            .maxTransactionResources(10)
            .simulationDurationSeconds(10)
            .numberOfIoProcessors(2)
            .numberOfCpuProcessors(2)
            .totalLocks(50)
            .numberOfReconcilerProcessors(1)
            .build();

        assertEquals(ConcurrencyModel.BASE, params.concurrencyModel);
        assertEquals(1, params.numberOfReconcilerProcessors);
    }

    @Test
    void baseModeUsesZeroLockTime() {
        SimulatorParameters params = SimulatorParameters.builder()
            .concurrencyModel(ConcurrencyModel.BASE)
            .totalModels(5)
            .totalResources(50)
            .ioTimePerResource(10)
            .cpuTimePerResource(5)
            .initialTransactions(3)
            .totalTransactionSteps(10)
            .maxTransactionModels(2)
            .maxTransactionResources(10)
            .simulationDurationSeconds(1)
            // 2, not 1: BASE carves the reconciler OUT of the CPU pool, so cpu=1 with
            // reconcilers=1 leaves ZERO foreground capacity. It used to be accepted and
            // silently zeroed the drain, so this ran BASE with bookkeepers inline - not
            // what the test name says. 1 foreground + 1 reconciler.
            .numberOfIoProcessors(2)
            .numberOfCpuProcessors(2)
            .totalLocks(20)
            .build();

        ModelFactory modelFactory = new ModelFactory(params.instancePoolSize);
        List<Model> modelPool = modelFactory.createModels(params.propertiesPerModel);
        
        SimulatorConfig config = SimulatorConfig.fromParameters(params, modelPool);
        SimulatorContext context = SimulatorContext.create(config);
        Simulator sim = new Simulator(context, StepMode.FIXED_TIME);

        // Run a short simulation
        sim.run();

        // Verify no lock time was consumed
        long totalLockTime = context.logger().getTotalLockTime();
        assertEquals(0, totalLockTime, "BASE mode should use 0ms lock time");
    }

    @Test
    void baseModeCompletesTransactions() {
        SimulatorParameters params = SimulatorParameters.builder()
            .concurrencyModel(ConcurrencyModel.BASE)
            .totalModels(3)
            .totalResources(30)
            .ioTimePerResource(5)
            .cpuTimePerResource(2)
            .initialTransactions(5)
            .totalTransactionSteps(50)  // More steps
            .maxTransactionModels(2)
            .maxTransactionResources(3)  // Fewer resources per transaction
            .simulationDurationSeconds(5)  // Longer duration
            .numberOfIoProcessors(2)
            .numberOfCpuProcessors(2)
            .totalLocks(10)
            .build();

        ModelFactory modelFactory = new ModelFactory(params.instancePoolSize);
        List<Model> modelPool = modelFactory.createModels(params.propertiesPerModel);
        
        SimulatorConfig config = SimulatorConfig.fromParameters(params, modelPool);
        SimulatorContext context = SimulatorContext.create(config);
        Simulator sim = new Simulator(context, StepMode.FIXED_TIME);

        sim.run();

        // Verify transactions were completed
        assertTrue(Transaction.getCompletedTransactions() > 0, 
                   "BASE mode should complete transactions");
    }

    @Test
    void baseModeWithMultipleProcessors() {
        SimulatorParameters params = SimulatorParameters.builder()
            .concurrencyModel(ConcurrencyModel.BASE)
            .totalModels(10)
            .totalResources(100)
            .ioTimePerResource(10)
            .cpuTimePerResource(5)
            .initialTransactions(10)
            .totalTransactionSteps(50)
            .maxTransactionModels(3)
            .maxTransactionResources(10)
            .simulationDurationSeconds(2)
            .numberOfIoProcessors(4)  // Multiple processors
            .numberOfCpuProcessors(4)
            .totalLocks(50)
            .build();

        ModelFactory modelFactory = new ModelFactory(params.instancePoolSize);
        List<Model> modelPool = modelFactory.createModels(params.propertiesPerModel);
        
        SimulatorConfig config = SimulatorConfig.fromParameters(params, modelPool);
        SimulatorContext context = SimulatorContext.create(config);
        Simulator sim = new Simulator(context, StepMode.FIXED_TIME);

        sim.run();

        // Verify no lock time with multiple processors
        assertEquals(0, context.logger().getTotalLockTime(), 
                     "BASE mode should use 0ms lock time even with multiple processors");
    }

    @Test
    void baseModeHandlesOverlappingResources() {
        SimulatorParameters params = SimulatorParameters.builder()
            .concurrencyModel(ConcurrencyModel.BASE)
            .totalModels(5)
            .totalResources(20)  // Smaller resource pool
            .ioTimePerResource(5)
            .cpuTimePerResource(2)
            .initialTransactions(10)  // More transactions than resources
            .totalTransactionSteps(100)  // More steps to ensure completion
            .maxTransactionModels(2)
            .maxTransactionResources(3)  // Fewer resources per transaction
            .semanticOverlap(0.5)  // High overlap
            .simulationDurationSeconds(10)  // Longer duration
            .numberOfIoProcessors(4)  // More processors
            .numberOfCpuProcessors(4)
            .totalLocks(10)
            .build();

        ModelFactory modelFactory = new ModelFactory(params.instancePoolSize);
        List<Model> modelPool = modelFactory.createModels(params.propertiesPerModel);
        
        SimulatorConfig config = SimulatorConfig.fromParameters(params, modelPool);
        SimulatorContext context = SimulatorContext.create(config);
        Simulator sim = new Simulator(context, StepMode.FIXED_TIME);

        sim.run();

        // Verify transactions completed despite overlapping resources
        assertTrue(Transaction.getCompletedTransactions() > 0,
                   "BASE should handle overlapping resources without blocking");
        assertEquals(0, context.logger().getTotalLockTime(),
                     "No lock time should be used even with overlapping resources");
    }

    @Test
    void baseModeName() {
        assertEquals("BASE", ConcurrencyModel.BASE.name());
    }
}
