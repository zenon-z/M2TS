package de.tum.tssimulator.state;

import java.util.Objects;

import de.tum.tssimulator.core.Resource;

/**
 * Represents a dirty target resource in a specific model.
 * Used for fan-out collapse: multiple CPR triggers targeting the same (modelName, resource)
 * collapse to a single dirty entry, preventing redundant bookkeeper spawning.
 */
public record DirtyKey(String modelName, Resource resource) {

    // Genuinely used as a live Map key (DirtySetManager.dirtySince), not just looked up once -
    // give it the same explicit hashCode() treatment as Resource rather than leaning on the
    // generated record hashCode transitively.
    @Override
    public int hashCode() {
        return Objects.hash(modelName, resource);
    }
}
