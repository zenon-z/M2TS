package de.tum.tssimulator.core.program;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A model-agnostic transaction program template. Unlike {@link TransactionProgram}, a shape
 * doesn't know which model it will land on - it declares its variables' property requirements
 * by <b>slot index</b> ({@code variableSlots}), resolved against a concrete model's declared
 * properties at instantiation time (see {@link TransactionProgramFactory}).
 */
public record ProgramShape(
        String name,
        List<String> variables,
        Map<String, Integer> variableSlots, // variable -> property slot index
        List<Step> steps, // total order
        double weight) {

    public ProgramShape {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("ProgramShape name must not be blank");
        }
        if (variables == null || variables.isEmpty()) {
            throw new IllegalArgumentException("ProgramShape '" + name + "' must declare at least one variable");
        }
        if (variableSlots == null || !variableSlots.keySet().equals(Set.copyOf(variables))) {
            throw new IllegalArgumentException(
                    "ProgramShape '" + name + "' variableSlots must declare a slot for exactly each variable");
        }
        for (int slot : variableSlots.values()) {
            if (slot < 0) {
                throw new IllegalArgumentException("ProgramShape '" + name + "' has a negative slot index");
            }
        }
        if (steps == null || steps.isEmpty()) {
            throw new IllegalArgumentException("ProgramShape '" + name + "' must have at least one step");
        }
        for (Step step : steps) {
            if (!variableSlots.containsKey(step.getVariable())) {
                throw new IllegalArgumentException("ProgramShape '" + name
                        + "' has a step referencing undeclared variable '" + step.getVariable() + "'");
            }
        }
        if (weight <= 0) {
            throw new IllegalArgumentException("ProgramShape '" + name + "' weight must be > 0");
        }
        variables = List.copyOf(variables);
        variableSlots = Map.copyOf(variableSlots);
        steps = List.copyOf(steps);
    }

    /** Number of distinct property slots this shape requires, e.g. slots {0,1} -> 2. */
    public int requiredProperties() {
        return variableSlots.values().stream().mapToInt(Integer::intValue).max().orElseThrow() + 1;
    }
}
