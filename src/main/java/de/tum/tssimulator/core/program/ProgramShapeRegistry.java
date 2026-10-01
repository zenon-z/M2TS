package de.tum.tssimulator.core.program;

import java.util.List;
import java.util.Map;

/**
 * Hand-authored default catalog of transaction program shapes, written in Shasha-style
 * notation. Static - needs no model pool.
 *
 * <p>Models and properties are generated from {@code propertiesPerModel} directly, and shapes are
 * turned into concrete {@link TransactionProgram}s via {@link TransactionProgramFactory}.
 */
public class ProgramShapeRegistry {

    private ProgramShapeRegistry() {
    }

    public static List<ProgramShape> defaultShapes() {
        ProgramShape balance = new ProgramShape(
                "Balance",
                List.of("x"),
                Map.of("x", 0),
                List.of(new Step(OperationType.READ, "x")),
                0.30);

        ProgramShape deposit = new ProgramShape(
                "Deposit",
                List.of("x"),
                Map.of("x", 0),
                List.of(new Step(OperationType.READ, "x"), new Step(OperationType.WRITE, "x")),
                0.15);

        ProgramShape withdraw = new ProgramShape(
                "Withdraw",
                List.of("x"),
                Map.of("x", 0),
                List.of(new Step(OperationType.READ, "x"), new Step(OperationType.WRITE, "x")),
                0.15);

        // Same property, distinct instances - the "must not chop" fixture.
        ProgramShape transfer = new ProgramShape(
                "Transfer",
                List.of("x", "y"),
                Map.of("x", 0, "y", 0),
                List.of(
                        new Step(OperationType.READ, "x"),
                        new Step(OperationType.WRITE, "x"),
                        new Step(OperationType.READ, "y"),
                        new Step(OperationType.WRITE, "y")),
                0.20);

        // Distinct properties - the only shape with chop potential.
        ProgramShape amalgamate = new ProgramShape(
                "Amalgamate",
                List.of("x", "y"),
                Map.of("x", 0, "y", 1),
                List.of(
                        new Step(OperationType.READ, "x"),
                        new Step(OperationType.READ, "y"),
                        new Step(OperationType.WRITE, "x"),
                        new Step(OperationType.WRITE, "y")),
                0.20);

        return List.of(balance, deposit, withdraw, transfer, amalgamate);
    }
}
