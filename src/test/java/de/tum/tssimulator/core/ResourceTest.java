package de.tum.tssimulator.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;

class ResourceTest {

    @Test
    void rejectsNonPositiveInstanceId() {
        List<Model> models = new ModelFactory(1).createModels(List.of(1));
        Property property = models.get(0).properties().get(0);

        assertThrows(IllegalArgumentException.class, () -> new Resource(0, property));
        assertThrows(IllegalArgumentException.class, () -> new Resource(-1, property));
    }

    @Test
    void modelDerivesFromProperty() {
        List<Model> models = new ModelFactory(1).createModels(List.of(1));
        Property property = models.get(0).properties().get(0);
        Resource resource = new Resource(1, property);

        assertEquals(resource.property().model(), resource.model());
        assertEquals(models.get(0), resource.model());
    }
}
