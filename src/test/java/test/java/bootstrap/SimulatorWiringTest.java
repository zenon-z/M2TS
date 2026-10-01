package test.java.bootstrap;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import org.junit.jupiter.api.Test;

import de.tum.tssimulator.bootstrap.SimulatorContext;
import de.tum.tssimulator.config.SimulatorConfig;
import de.tum.tssimulator.core.Model;
import de.tum.tssimulator.core.ModelFactory;

class SimulatorWiringTest {

    @Test
    void contextBuildsWithoutNPEs() {
        // Build a minimal config for wiring matching SimulatorConfig signature
        List<Model> modelPool = new ModelFactory(1).createModels(List.of(1));
        SimulatorConfig cfg = new SimulatorConfig(
            1, 1,               // totalModels, totalResources
            0L, 0L,             // ioTimePerResource, cpuTimePerResource
            0, 0, 1,            // initialTransactionCount, totalTransactionSteps, simulationDurationSeconds
            modelPool,          // modelPool
            0, 0,               // maxTransactionModels, maxTransactionResources
            0L, 0L,             // lockIOTime, lockCPUTimePerResource
            1, 1,               // numIoProcessors, numCpuProcessors
            1                   // totalLocks
        );
        assertDoesNotThrow(() -> SimulatorContext.create(cfg));
    }
}
