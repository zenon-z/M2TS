package de.tum.tssimulator.core.program;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import de.tum.tssimulator.core.Model;
import de.tum.tssimulator.core.Property;

/**
 * Immutable, model-bound instantiation of a {@link ProgramShape}: a named transaction
 * program, expressed in Shasha-style chopping notation, whose variables are each resolved
 * to a concrete {@link Property} on exactly one {@link Model}.
 *
 * <p>Built exclusively by {@link TransactionProgramFactory}; {@code id} is
 * {@code "<shapeName>@<modelName>"}.
 */
public record TransactionProgram(
        String id,
        Model model,
        List<String> variables,
        Map<String, Property> variableProperties,
        List<Step> steps,
        double weight) {

    public TransactionProgram {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("TransactionProgram id must not be blank");
        }
        if (model == null) {
            throw new IllegalArgumentException("TransactionProgram '" + id + "' must have a model");
        }
        if (variables == null || variables.isEmpty()) {
            throw new IllegalArgumentException("TransactionProgram '" + id + "' must declare at least one variable");
        }
        Set<String> seen = new HashSet<>();
        for (String variable : variables) {
            if (variable == null || variable.isBlank()) {
                throw new IllegalArgumentException("TransactionProgram '" + id + "' has a blank variable name");
            }
            if (!seen.add(variable)) {
                throw new IllegalArgumentException(
                        "TransactionProgram '" + id + "' declares duplicate variable '" + variable + "'");
            }
        }
        if (variableProperties == null || !variableProperties.keySet().equals(seen)) {
            throw new IllegalArgumentException(
                    "TransactionProgram '" + id + "' variableProperties must declare a property for exactly each variable");
        }
        // All declared properties belong to one model - holds by construction (the factory only
        // ever resolves slots against a single model's own properties()), asserted here anyway.
        for (Property property : variableProperties.values()) {
            if (!property.model().equals(model)) {
                throw new IllegalArgumentException(
                        "TransactionProgram '" + id + "' has a variable property belonging to model '"
                                + property.model() + "', expected '" + model + "'");
            }
        }
        if (steps == null || steps.isEmpty()) {
            throw new IllegalArgumentException("TransactionProgram '" + id + "' must have at least one step");
        }
        for (Step step : steps) {
            if (!seen.contains(step.getVariable())) {
                throw new IllegalArgumentException(
                        "TransactionProgram '" + id + "' has a step referencing undeclared variable '"
                                + step.getVariable() + "'");
            }
        }
        if (weight <= 0) {
            throw new IllegalArgumentException("TransactionProgram '" + id + "' weight must be > 0");
        }

        variables = List.copyOf(variables);
        variableProperties = Map.copyOf(variableProperties);
        steps = List.copyOf(steps);
    }
}
