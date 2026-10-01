package test.java.bootstrap;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;

import org.junit.jupiter.api.Test;

import de.tum.tssimulator.bootstrap.SimulatorContext;
import de.tum.tssimulator.config.SimulatorConfig;
import de.tum.tssimulator.config.SimulatorParameters;
import de.tum.tssimulator.core.Model;
import de.tum.tssimulator.core.ModelFactory;
import de.tum.tssimulator.execution.AbstractAdmissionController;
import de.tum.tssimulator.execution.StepMode;
import de.tum.tssimulator.execution.StepStrategyFactory;

class SimulatorParallelWiringIsolationTest {

    @Test
    void contextSpecificIntakeWiringDoesNotLeakAcrossContexts() {
        SimulatorConfig firstConfig = minimalConfig();
        SimulatorConfig secondConfig = minimalConfig();

        SimulatorContext first = SimulatorContext.create(firstConfig);
        SimulatorContext second = SimulatorContext.create(secondConfig);

        StepStrategyFactory.create(StepMode.FIXED_TIME, first);
        StepStrategyFactory.create(StepMode.FIXED_TIME, second);

        AbstractAdmissionController firstIntake = first.execution().intakeDispatcher();
        AbstractAdmissionController secondIntake = second.execution().intakeDispatcher();

        assertNotNull(firstIntake);
        assertNotNull(secondIntake);
        assertNotNull(firstIntake.getStepContext());
        assertNotNull(secondIntake.getStepContext());
        assertNotSame(firstIntake, secondIntake);
        assertNotSame(firstIntake.getStepContext(), secondIntake.getStepContext());
    }

    private static SimulatorConfig minimalConfig() {
        List<Model> modelPool = new ModelFactory(1).createModels(List.of(1));
        return SimulatorConfig.fromParameters(
                SimulatorParameters.builder()
                        .totalModels(1)
                        .semanticOverlap(0.0) // single-model pool: semanticOverlap>0 needs >=2 models
                        .ioTimePerResource(0)
                        .cpuTimePerResource(0)
                        .lockIOTimePerResource(0)
                        .lockCPUTimePerResource(0)
                        .initialTransactions(0)
                        .totalTransactionSteps(1)
                        .simulationDurationSeconds(1)
                        .maxTransactionModels(1)
                        .maxTransactionResources(1)
                        .numberOfIoProcessors(1)
                        .numberOfCpuProcessors(1)
                        .totalLocks(1)
                        .build(),
                modelPool);
    }
}
