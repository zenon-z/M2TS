package de.tum.tssimulator.core;

import java.util.Objects;

/**
 * A resource is an (instance, property) pair. This is the concrete, first-class replacement
 * for the {@code String} resource id used throughout the engine.
 */
public record Resource(int instanceId, Property property) {

    public Resource {
        if (instanceId < 1) {
            throw new IllegalArgumentException("instanceId must be >= 1, got " + instanceId);
        }
    }

    public Model model() {
        return property.model();
    }

    // A record's implicit hashCode is documented as unspecified across JDK releases - safe for
    // same-run lookups but not something to rely on going forward. Built from Property's
    // now-specified hashCode, this closes that out permanently. equals() stays generated:
    // record component equality is fully specified regardless of JDK.
    @Override
    public int hashCode() {
        return Objects.hash(instanceId, property);
    }

    // Display/logging only - nothing parses this back.
    @Override
    public String toString() {
        return "i" + instanceId + "." + property;
    }
}
