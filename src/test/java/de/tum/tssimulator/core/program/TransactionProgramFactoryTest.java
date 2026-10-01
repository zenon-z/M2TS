package de.tum.tssimulator.core.program;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import de.tum.tssimulator.core.Model;
import de.tum.tssimulator.core.ModelFactory;

class TransactionProgramFactoryTest {

    @Test
    void shapeAssignmentIsDeterministic() {
        List<Model> modelsA = new ModelFactory(10).createModels(List.of(5, 5, 5));
        List<Model> modelsB = new ModelFactory(10).createModels(List.of(5, 5, 5));
        List<ProgramShape> shapes = ProgramShapeRegistry.defaultShapes();

        List<TransactionProgram> first = TransactionProgramFactory.createPrograms(shapes, modelsA);
        List<TransactionProgram> second = TransactionProgramFactory.createPrograms(shapes, modelsB);

        assertEquals(first.size(), second.size());
        for (int i = 0; i < first.size(); i++) {
            assertEquals(first.get(i).id(), second.get(i).id());
        }
    }

    @Test
    void assignmentSkipsTooSmallModelsAndWraps() {
        // propertiesPerModel = [5,4,3,1,1] - Amalgamate (index 4, needs 2 properties) lands on
        // Model1 after Model5 (1 property) is skipped and wraps around.
        List<Model> models = new ModelFactory(5).createModels(List.of(5, 4, 3, 1, 1));
        List<ProgramShape> shapes = ProgramShapeRegistry.defaultShapes();

        List<TransactionProgram> programs = TransactionProgramFactory.createPrograms(shapes, models);

        TransactionProgram balance = findByShapeName(programs, "Balance");
        TransactionProgram deposit = findByShapeName(programs, "Deposit");
        TransactionProgram withdraw = findByShapeName(programs, "Withdraw");
        TransactionProgram transfer = findByShapeName(programs, "Transfer");
        TransactionProgram amalgamate = findByShapeName(programs, "Amalgamate");

        assertEquals("Model1", balance.model().getName());
        assertEquals("Model2", deposit.model().getName());
        assertEquals("Model3", withdraw.model().getName());
        assertEquals("Model4", transfer.model().getName());
        assertEquals("Model1", amalgamate.model().getName(), "Model5 (1 property) is too small; wraps to Model1");
    }

    @Test
    void throwsNamingShapeAndRequiredPropertyCountWhenNoModelFits() {
        List<Model> models = new ModelFactory(5).createModels(List.of(1));
        ProgramShape needsTwoProperties = new ProgramShape(
                "TooBig",
                List.of("x", "y"),
                Map.of("x", 0, "y", 1),
                List.of(new Step(OperationType.READ, "x"), new Step(OperationType.READ, "y")),
                1.0);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> TransactionProgramFactory.createPrograms(List.of(needsTwoProperties), models));
        assertTrue(ex.getMessage().contains("TooBig"));
        assertTrue(ex.getMessage().contains("2"));
    }

    @Test
    void programIdIsShapeNameAtModelName() {
        List<Model> models = new ModelFactory(10).createModels(List.of(2));
        List<TransactionProgram> programs = TransactionProgramFactory.createPrograms(
                List.of(ProgramShapeRegistry.defaultShapes().get(0)), models);

        assertEquals("Balance@Model1", programs.get(0).id());
    }

    private static TransactionProgram findByShapeName(List<TransactionProgram> programs, String shapeName) {
        return programs.stream().filter(p -> p.id().startsWith(shapeName + "@")).findFirst().orElseThrow();
    }
}
