package de.tum.tssimulator.core.program;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import de.tum.tssimulator.core.Model;
import de.tum.tssimulator.core.ModelFactory;
import de.tum.tssimulator.core.Property;

class TransactionProgramTest {

    // Both models must come from the same ModelFactory pool - otherwise they'd both be
    // independently named "Model1" and collide under Model's name-based equality.
    private static final List<Model> POOL = new ModelFactory(10).createModels(List.of(2, 1));
    private static final Model MODEL = POOL.get(0);
    private static final Property X = MODEL.properties().get(0);
    private static final Property Y = MODEL.properties().get(1);
    private static final Property OTHER_MODEL_PROPERTY = POOL.get(1).properties().get(0);

    @Test
    void validProgramConstructsSuccessfully() {
        TransactionProgram program = new TransactionProgram(
                "Transfer@" + MODEL.getName(),
                MODEL,
                List.of("x", "y"),
                Map.of("x", X, "y", Y),
                List.of(
                        new Step(OperationType.READ, "x"),
                        new Step(OperationType.WRITE, "x"),
                        new Step(OperationType.READ, "y"),
                        new Step(OperationType.WRITE, "y")),
                1.0);

        assertEquals("Transfer@" + MODEL.getName(), program.id());
        assertEquals(MODEL, program.model());
        assertEquals(List.of("x", "y"), program.variables());
        assertEquals(4, program.steps().size());
        assertEquals(1.0, program.weight());
    }

    @Test
    void rejectsBlankId() {
        assertThrows(IllegalArgumentException.class, () -> new TransactionProgram(
                "  ", MODEL, List.of("x"), Map.of("x", X), List.of(new Step(OperationType.READ, "x")), 1.0));
    }

    @Test
    void rejectsNullModel() {
        assertThrows(IllegalArgumentException.class, () -> new TransactionProgram(
                "P", null, List.of("x"), Map.of("x", X), List.of(new Step(OperationType.READ, "x")), 1.0));
    }

    @Test
    void rejectsEmptyVariables() {
        assertThrows(IllegalArgumentException.class, () -> new TransactionProgram(
                "P", MODEL, List.of(), Map.of(), List.of(new Step(OperationType.READ, "x")), 1.0));
    }

    @Test
    void rejectsBlankVariableEntry() {
        assertThrows(IllegalArgumentException.class, () -> new TransactionProgram(
                "P", MODEL, Arrays.asList("x", " "), Map.of("x", X, " ", Y),
                List.of(new Step(OperationType.READ, "x")), 1.0));
    }

    @Test
    void rejectsDuplicateVariables() {
        assertThrows(IllegalArgumentException.class, () -> new TransactionProgram(
                "P", MODEL, List.of("x", "x"), Map.of("x", X), List.of(new Step(OperationType.READ, "x")), 1.0));
    }

    @Test
    void rejectsMissingVariableProperty() {
        assertThrows(IllegalArgumentException.class, () -> new TransactionProgram(
                "P", MODEL, List.of("x", "y"), Map.of("x", X), List.of(new Step(OperationType.READ, "x")), 1.0));
    }

    @Test
    void rejectsPropertyFromAnotherModel() {
        assertThrows(IllegalArgumentException.class, () -> new TransactionProgram(
                "P", MODEL, List.of("x"), Map.of("x", OTHER_MODEL_PROPERTY),
                List.of(new Step(OperationType.READ, "x")), 1.0));
    }

    @Test
    void rejectsEmptySteps() {
        assertThrows(IllegalArgumentException.class, () -> new TransactionProgram(
                "P", MODEL, List.of("x"), Map.of("x", X), List.of(), 1.0));
    }

    @Test
    void rejectsStepReferencingUndeclaredVariable() {
        assertThrows(IllegalArgumentException.class, () -> new TransactionProgram(
                "P", MODEL, List.of("x"), Map.of("x", X), List.of(new Step(OperationType.READ, "y")), 1.0));
    }

    @Test
    void rejectsNonPositiveWeight() {
        assertThrows(IllegalArgumentException.class, () -> new TransactionProgram(
                "P", MODEL, List.of("x"), Map.of("x", X), List.of(new Step(OperationType.READ, "x")), 0.0));
        assertThrows(IllegalArgumentException.class, () -> new TransactionProgram(
                "P", MODEL, List.of("x"), Map.of("x", X), List.of(new Step(OperationType.READ, "x")), -1.0));
    }
}
