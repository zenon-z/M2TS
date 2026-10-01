package de.tum.tssimulator.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

class ModelTest {

    @Test
    void ownedResourcesCountMatchesPropertyCountTimesInstancePoolSize() {
        int instancePoolSize = 4;
        List<Model> models = new ModelFactory(instancePoolSize).createModels(List.of(5, 4, 3));

        assertEquals(3, models.size());
        assertEquals(5, models.get(0).propertyCount());
        assertEquals(4, models.get(1).propertyCount());
        assertEquals(3, models.get(2).propertyCount());

        for (Model model : models) {
            assertEquals(model.propertyCount() * instancePoolSize, model.getOwnedResources().size());
            assertEquals(model.propertyCount() * instancePoolSize, model.resourceCount());
        }
    }

    @Test
    void ownedResourcesAreInInstanceMajorOrder() {
        int instancePoolSize = 3;
        Model model = new ModelFactory(instancePoolSize).createModels(List.of(2)).get(0);

        List<Resource> resources = model.getOwnedResources();
        // instance-major: (i1,attr1), (i1,attr2), (i2,attr1), (i2,attr2), (i3,attr1), (i3,attr2)
        assertEquals(1, resources.get(0).instanceId());
        assertEquals(0, resources.get(0).property().index());
        assertEquals(1, resources.get(1).instanceId());
        assertEquals(1, resources.get(1).property().index());
        assertEquals(2, resources.get(2).instanceId());
        assertEquals(0, resources.get(2).property().index());
    }

    @Test
    void resourceSetsAreDisjointAcrossModels() {
        List<Model> models = new ModelFactory(5).createModels(List.of(5, 4, 3));
        Set<Resource> seen = new HashSet<>();
        for (Model model : models) {
            for (Resource r : model.getOwnedResources()) {
                assertEquals(true, seen.add(r), "resource " + r + " duplicated across models");
            }
        }
    }

    @Test
    void localOrdinalRoundTripsAgainstPublishedList() {
        List<Model> models = new ModelFactory(5).createModels(List.of(5, 4, 3));
        for (Model model : models) {
            List<Resource> owned = model.getOwnedResources();
            for (int j = 0; j < model.resourceCount(); j++) {
                assertEquals(j, model.localOrdinal(owned.get(j)));
            }
        }
    }

    @Test
    void localOrdinalRejectsResourceFromAnotherModel() {
        List<Model> models = new ModelFactory(5).createModels(List.of(2, 2));
        Model modelA = models.get(0);
        Model modelB = models.get(1);
        Resource resourceOwnedByB = modelB.getOwnedResources().get(0);

        assertThrows(IllegalArgumentException.class, () -> modelA.localOrdinal(resourceOwnedByB));
    }

    @Test
    void equalityIsByNameOnly() {
        List<Model> a = new ModelFactory(2).createModels(List.of(2));
        List<Model> b = new ModelFactory(2).createModels(List.of(2));
        assertEquals(a.get(0), b.get(0));
        assertEquals(a.get(0).hashCode(), b.get(0).hashCode());
    }

    @Test
    void resourceModelIdentityMatchesOwningModel() {
        Model model = new ModelFactory(2).createModels(List.of(2)).get(0);
        Resource r = model.getOwnedResources().get(0);
        assertSame(model.properties().get(0), r.property());
    }
}
