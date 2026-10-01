package de.tum.tssimulator.core.program;

import de.tum.tssimulator.core.Model;
import de.tum.tssimulator.core.Resource;

/**
 * Immutable, generation-time result of resolving a {@link Step} against a concrete
 * transaction instance: which resource it landed on. {@code model} is derived from the
 * resource ({@code resource.model()}), not stored separately.
 */
public class Operation {
    private final OperationType type;
    private final Resource resource;

    public Operation(OperationType type, Resource resource) {
        this.type = type;
        this.resource = resource;
    }

    public OperationType getType() {
        return type;
    }

    public Resource getResource() {
        return resource;
    }

    public Model getModel() {
        return resource.model();
    }
}
