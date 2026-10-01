package de.tum.tssimulator.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;

class ModelFactoryTest {

    @Test
    void createsOneModelPerScheduleEntryWithDeclaredPropertyCounts() {
        List<Model> models = new ModelFactory(5).createModels(List.of(5, 4, 3));

        assertEquals(3, models.size());
        assertEquals("Model1", models.get(0).getName());
        assertEquals("Model2", models.get(1).getName());
        assertEquals("Model3", models.get(2).getName());
        assertEquals(5, models.get(0).propertyCount());
        assertEquals(4, models.get(1).propertyCount());
        assertEquals(3, models.get(2).propertyCount());
    }

    @Test
    void resourceListSizePerModelMatchesPropertyCountTimesInstancePoolSize() {
        int instancePoolSize = 7;
        List<Model> models = new ModelFactory(instancePoolSize).createModels(List.of(5, 4, 3));
        for (Model model : models) {
            assertEquals(model.propertyCount() * instancePoolSize, model.getOwnedResources().size());
        }
    }

    @Test
    void rejectsEmptyOrNullSchedule() {
        ModelFactory factory = new ModelFactory(5);
        assertThrows(IllegalArgumentException.class, () -> factory.createModels(List.of()));
        assertThrows(IllegalArgumentException.class, () -> factory.createModels(null));
    }

    @Test
    void rejectsNonPositivePropertyCount() {
        ModelFactory factory = new ModelFactory(5);
        assertThrows(IllegalArgumentException.class, () -> factory.createModels(List.of(0)));
        assertThrows(IllegalArgumentException.class, () -> factory.createModels(List.of(2, -1)));
    }

    @Test
    void rejectsNonPositiveInstancePoolSize() {
        assertThrows(IllegalArgumentException.class, () -> new ModelFactory(0));
        assertThrows(IllegalArgumentException.class, () -> new ModelFactory(-1));
    }
}
