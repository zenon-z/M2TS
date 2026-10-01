package de.tum.tssimulator.core;

import java.util.List;
import java.util.Objects;
import java.util.stream.IntStream;

/**
 * A model owns a fixed set of properties and, via {@link #getOwnedResources()}, the
 * cross-product of those properties with the global instance id range
 * {@code 1..instancePoolSize}.
 *
 * <p>Package-private constructor: {@link ModelFactory} is the only construction path.
 */
public final class Model {
    private final String name;
    private final List<Property> properties;
    private final int instancePoolSize;
    private final List<Resource> ownedResources; // cached, built once from resourceAt()

    Model(String name, int propertyCount, int instancePoolSize) {
        this.name = name;
        this.instancePoolSize = instancePoolSize;
        this.properties = IntStream.range(0, propertyCount)
                .mapToObj(i -> new Property(this, "attr" + (i + 1), i)) // index = declaration order, 0-based
                .toList();
        this.ownedResources = IntStream.range(0, resourceCount())
                .mapToObj(this::resourceAt)
                .toList();
    }

    public String getName() {
        return name;
    }

    public List<Property> properties() {
        return properties;
    }

    public int propertyCount() {
        return properties.size();
    }

    public int instancePoolSize() {
        return instancePoolSize;
    }

    public int resourceCount() {
        return instancePoolSize * properties.size();
    }

    /** The single definition of this model's resource ordering (instance-major). */
    public int localOrdinal(Resource r) {
        if (r.property().model() != this) { // identity check is correct: factories are the only
            throw new IllegalArgumentException( // construction path, one object per model
                    "Resource " + r + " does not belong to " + name);
        }
        return (r.instanceId() - 1) * properties.size() + r.property().index();
    }

    /** Inverse of localOrdinal - used once, at construction, to build ownedResources. */
    private Resource resourceAt(int localOrdinal) {
        int instanceId = localOrdinal / properties.size() + 1;
        Property property = properties.get(localOrdinal % properties.size());
        return new Resource(instanceId, property);
    }

    public List<Resource> getOwnedResources() {
        return ownedResources; // cached - no recomputation per call
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Model other)) return false;
        return name.equals(other.name);
    }

    // Hand-written, built from String#hashCode() - stable across JDK versions, unlike the
    // default identity-based hashCode. MUST NOT include `properties`/`ownedResources`:
    // Property.equals/hashCode reads Model.getName() directly (not equals/hashCode), so this
    // stays acyclic, but the fields are still excluded on principle - a Model's identity is its
    // name.
    @Override
    public int hashCode() {
        return Objects.hash(name);
    }

    @Override
    public String toString() {
        return name;
    }
}
