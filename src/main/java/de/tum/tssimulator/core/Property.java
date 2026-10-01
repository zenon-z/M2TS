package de.tum.tssimulator.core;

import java.util.Objects;

/**
 * The static conflict token for resource access: the property a variable is declared on.
 * Two accesses on different properties provably never touch the same resource, regardless
 * of which instances they bind to at runtime.
 *
 * <p>Package-private constructor: {@link Model} is the only construction path, one object
 * per (model, property) pair.
 */
public final class Property {
    private final Model model;
    private final String name;
    private final int index; // position in model's declaration order (0-based)

    Property(Model model, String name, int index) {
        this.model = model;
        this.name = name;
        this.index = index;
    }

    public Model model() {
        return model;
    }

    public String name() {
        return name;
    }

    /** Position in the owning model's declaration order (0-based). */
    public int index() {
        return index;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Property other)) return false;
        return model.getName().equals(other.model.getName()) && name.equals(other.name);
    }

    // Hand-written, built from String#hashCode() (JLS-specified) rather than the default
    // identity-based hashCode - stable across JVM/JDK runs. Must NOT include Model.hashCode()
    // recursively calling back into Property; Objects.hash(model.getName(), name) avoids that.
    @Override
    public int hashCode() {
        return Objects.hash(model.getName(), name);
    }

    // Display/logging only - never identity, never parsed.
    @Override
    public String toString() {
        return model.getName() + "." + name;
    }
}
