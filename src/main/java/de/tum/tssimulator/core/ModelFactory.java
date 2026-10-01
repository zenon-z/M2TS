package de.tum.tssimulator.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the model pool. {@code instancePoolSize} is shared across every model this factory
 * creates; each model's property count comes from {@code propertiesPerModel}.
 *
 * <p>The resource cross-product and its instance-major ordering are owned entirely by
 * {@link Model} itself ({@code localOrdinal}/{@code resourceAt}) - not duplicated here.
 */
public class ModelFactory {
    private final int instancePoolSize;

    public ModelFactory(int instancePoolSize) {
        if (instancePoolSize < 1) {
            throw new IllegalArgumentException("instancePoolSize must be >= 1, got " + instancePoolSize);
        }
        this.instancePoolSize = instancePoolSize;
    }

    public int instancePoolSize() {
        return instancePoolSize;
    }

    /**
     * Model <i>i</i> is named {@code Model{i+1}} with {@code propertiesPerModel.get(i)}
     * properties named {@code attr1..attrK}.
     */
    public List<Model> createModels(List<Integer> propertiesPerModel) {
        if (propertiesPerModel == null || propertiesPerModel.isEmpty()) {
            throw new IllegalArgumentException("propertiesPerModel must not be empty");
        }
        List<Model> models = new ArrayList<>(propertiesPerModel.size());
        for (int i = 0; i < propertiesPerModel.size(); i++) {
            int propertyCount = propertiesPerModel.get(i);
            if (propertyCount < 1) {
                throw new IllegalArgumentException(
                        "propertiesPerModel[" + i + "] must be >= 1, got " + propertyCount);
            }
            models.add(new Model("Model" + (i + 1), propertyCount, instancePoolSize));
        }
        return models;
    }
}
