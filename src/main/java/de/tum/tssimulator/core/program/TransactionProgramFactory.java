package de.tum.tssimulator.core.program;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import de.tum.tssimulator.core.Model;
import de.tum.tssimulator.core.Property;

/**
 * Turns a shape catalog into a concrete program list: one {@link TransactionProgram} per
 * {@link ProgramShape}, deterministically assigned to a model.
 *
 * <p>Round-robin by shape declaration index, advancing past models that are too small to hold
 * the shape's required property count. Pure function of {@code propertiesPerModel} (i.e. of
 * {@code modelPool.size()} and each model's {@code propertyCount()}) - same inputs always
 * produce the same shape-&gt;model mapping.
 */
public class TransactionProgramFactory {

    private TransactionProgramFactory() {
    }

    public static List<TransactionProgram> createPrograms(List<ProgramShape> shapes, List<Model> modelPool) {
        if (shapes == null || shapes.isEmpty()) {
            throw new IllegalArgumentException("shapes must not be empty");
        }
        if (modelPool == null || modelPool.isEmpty()) {
            throw new IllegalArgumentException("modelPool must not be empty");
        }

        int modelCount = modelPool.size();
        List<TransactionProgram> programs = new ArrayList<>(shapes.size());

        for (int i = 0; i < shapes.size(); i++) {
            ProgramShape shape = shapes.get(i);
            Model model = assignModel(shape, i, modelPool, modelCount);
            programs.add(instantiate(shape, model));
        }

        return programs;
    }

    private static Model assignModel(ProgramShape shape, int shapeIndex, List<Model> modelPool, int modelCount) {
        int m = shapeIndex % modelCount;
        int tried = 0;
        while (modelPool.get(m).propertyCount() < shape.requiredProperties()) {
            m = (m + 1) % modelCount;
            if (++tried == modelCount) {
                throw new IllegalArgumentException(
                        "Shape '" + shape.name() + "' needs " + shape.requiredProperties()
                                + " properties; no model has that many");
            }
        }
        return modelPool.get(m);
    }

    private static TransactionProgram instantiate(ProgramShape shape, Model model) {
        Map<String, Property> variableProperties = new HashMap<>();
        for (String variable : shape.variables()) {
            int slot = shape.variableSlots().get(variable);
            variableProperties.put(variable, model.properties().get(slot));
        }
        String id = shape.name() + "@" + model.getName();
        return new TransactionProgram(id, model, shape.variables(), variableProperties, shape.steps(), shape.weight());
    }
}
