package de.tum.tssimulator.core;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

class PropertyTest {

    @Test
    void equalityIsPerModelAndName() {
        List<Model> models = new ModelFactory(1).createModels(List.of(2, 2));
        Model model1 = models.get(0);
        Model model2 = models.get(1);

        Property model1Attr1 = model1.properties().get(0);
        Property model2Attr1 = model2.properties().get(0);
        Property model1Attr1Again = model1.properties().get(0);

        assertNotEquals(model1Attr1, model2Attr1, "Property(Model1,attr1) must differ from Property(Model2,attr1)");
        assertEquals(model1Attr1, model1Attr1Again, "same model/name must be equal");
        assertEquals(model1Attr1.hashCode(), model1Attr1Again.hashCode());
    }

    @Test
    void noEqualsHashCodeRecursionBetweenModelAndProperty() {
        List<Model> models = new ModelFactory(1).createModels(List.of(2));
        Model model = models.get(0);
        Property property = model.properties().get(0);

        // A plain equality/hashCode call on either is enough to catch infinite recursion.
        assertDoesNotThrow(() -> model.equals(model));
        assertDoesNotThrow(model::hashCode);
        assertDoesNotThrow(() -> property.equals(property));
        assertDoesNotThrow(property::hashCode);
    }

    @Test
    void indexReflectsDeclarationOrder() {
        List<Model> models = new ModelFactory(1).createModels(List.of(3));
        Model model = models.get(0);
        for (int i = 0; i < model.properties().size(); i++) {
            assertEquals(i, model.properties().get(i).index());
        }
    }
}
