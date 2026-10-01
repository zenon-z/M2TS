package de.tum.tssimulator.core.program;

/**
 * Immutable program-template element: an operation on a symbolic resource variable.
 * Pure structure, no concrete resource — {@code variable} is just the resource-variable
 * name as declared in the owning {@link TransactionProgram}.
 */
public class Step {
    private final OperationType type;
    private final String variable;

    public Step(OperationType type, String variable) {
        this.type = type;
        this.variable = variable;
    }

    public OperationType getType() {
        return type;
    }

    public String getVariable() {
        return variable;
    }
}
