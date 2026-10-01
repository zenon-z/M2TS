package de.tum.tssimulator.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import de.tum.tssimulator.core.Model;
import de.tum.tssimulator.core.ModelFactory;
import de.tum.tssimulator.core.Property;
import de.tum.tssimulator.core.program.OperationType;
import de.tum.tssimulator.core.program.Step;
import de.tum.tssimulator.core.program.TransactionProgram;

class SimulatorConfigBuilderValidationTest {

    private static List<Model> models(List<Integer> propertiesPerModel, int instancePoolSize) {
        return new ModelFactory(instancePoolSize).createModels(propertiesPerModel);
    }

    private static SimulatorConfig.Builder baseBuilder(List<Model> modelPool) {
        return new SimulatorConfig.Builder()
                .totalModels(modelPool.size())
                .ioTimePerResource(1)
                .cpuTimePerResource(1)
                .initialTransactionCount(1)
                .totalTransactionSteps(1)
                .simulationDurationSeconds(1)
                .modelPool(modelPool)
                .maxTransactionModels(2)
                .maxTransactionResources(5)
                .lockIOTime(1)
                .lockCPUTimePerResource(1)
                .numIoProcessors(1)
                .numCpuProcessors(1)
                .totalLocks(10);
    }

    // x and y share the model's first property (like the shape catalog's "Transfer"): same
    // property, distinct instances.
    private static TransactionProgram transferProgram(Model model) {
        Property x = model.properties().get(0);
        return new TransactionProgram(
                "Transfer@" + model.getName(),
                model,
                List.of("x", "y"),
                Map.of("x", x, "y", x),
                List.of(
                        new Step(OperationType.READ, "x"),
                        new Step(OperationType.WRITE, "x"),
                        new Step(OperationType.READ, "y"),
                        new Step(OperationType.WRITE, "y")),
                1.0);
    }

    @Test
    void semanticOverlapAboveZeroWithOneModelThrows() {
        List<Model> modelPool = models(List.of(1), 20);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> baseBuilder(modelPool)
                .semanticOverlap(0.05)
                .build());
        org.junit.jupiter.api.Assertions.assertTrue(ex.getMessage().contains("semanticOverlap"));
    }

    @Test
    void semanticOverlapZeroWithOneModelDoesNotThrow() {
        List<Model> modelPool = models(List.of(1), 20);
        assertDoesNotThrow(() -> baseBuilder(modelPool)
                .semanticOverlap(0.0)
                .build());
    }

    @Test
    void transactionProgramsModeWithEmptyPoolThrows() {
        List<Model> modelPool = models(List.of(1, 1), 20);
        assertThrows(IllegalArgumentException.class, () -> baseBuilder(modelPool)
                .transactionGenerationMode(TransactionGenerationMode.TRANSACTION_PROGRAMS)
                .programPool(List.of())
                .build());
    }

    @Test
    void shortfallThrowsWhenInstancePoolSizeBelowSharedPropertyDraw() {
        // instancePoolSize=1, but Transfer's x/y share one property and need 2 distinct
        // instances - the distinct-instance draw at generation time cannot succeed.
        List<Model> modelPool = models(List.of(1), 1);
        TransactionProgram transfer = transferProgram(modelPool.get(0));

        assertThrows(IllegalArgumentException.class, () -> baseBuilder(modelPool)
                .transactionGenerationMode(TransactionGenerationMode.TRANSACTION_PROGRAMS)
                .programPool(List.of(transfer))
                .build());
    }

    @Test
    void noShortfallDoesNotThrowWhenInstancePoolSizeIsSufficient() {
        List<Model> modelPool = models(List.of(1, 1), 20); // well above Transfer's 2-instance need
        TransactionProgram transfer = transferProgram(modelPool.get(0));

        assertDoesNotThrow(() -> baseBuilder(modelPool)
                .transactionGenerationMode(TransactionGenerationMode.TRANSACTION_PROGRAMS)
                .programPool(List.of(transfer))
                .build());
    }

    @Test
    void randomOperationsModeWithUndersizedInstancePoolAndNonEmptyPoolDoesNotThrow() {
        // Mode-guard regression: programPool present + a program whose needs exceed
        // instancePoolSize must not fail config-build when the mode is RANDOM_OPERATIONS, since
        // that mode never touches programPool at all.
        List<Model> modelPool = models(List.of(1), 1);
        TransactionProgram transfer = transferProgram(modelPool.get(0));

        assertDoesNotThrow(() -> baseBuilder(modelPool)
                .maxTransactionResources(1) // satisfy RANDOM_OPERATIONS' own shortfall bound
                .semanticOverlap(0.0) // single-model pool: semanticOverlap>0 needs >=2 models, unrelated to this test
                .transactionGenerationMode(TransactionGenerationMode.RANDOM_OPERATIONS)
                .programPool(List.of(transfer))
                .build());
    }
}
