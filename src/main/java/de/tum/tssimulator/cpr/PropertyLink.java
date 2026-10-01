package de.tum.tssimulator.cpr;

import de.tum.tssimulator.core.Property;

/**
 * A cross-property relationship declaration between two models: {@code source} on one model,
 * {@code target} on another. CPRs are cross-model by definition, so construction rejects a
 * same-model pair.
 *
 * <p>Type only, for now - no registry, no closure computation, no traversal, no consumer.
 * Wiring this into the chopping/CPR machinery is a later, separate plan.
 */
public record PropertyLink(Property source, Property target) {

    public PropertyLink {
        if (source == null || target == null) {
            throw new IllegalArgumentException("PropertyLink requires both a source and a target property");
        }
        if (source.model().equals(target.model())) {
            throw new IllegalArgumentException(
                    "PropertyLink must be cross-model: source and target are both on " + source.model());
        }
    }
}
