package de.tum.tssimulator.cpr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import de.tum.tssimulator.bookkeeping.CprTopologyMode;
import de.tum.tssimulator.core.Model;
import de.tum.tssimulator.core.ModelFactory;
import de.tum.tssimulator.core.Property;

class CprSchemaTest {

    @Test
    void closureTerminatesOnARingCycleAndTouchesEveryModel() {
        List<Model> models = createModels(4, 1);
        CprSchema schema = new CprSchemaBuilder(models, CprTopologyMode.RING, 1.0, 1.0, 1, 1L, null, false).build();

        Property p = models.get(0).properties().get(0);
        Set<Property> closure = schema.closure(p);

        assertEquals(3, closure.size(), "closure should reach the other 3 models, not loop back to p's own model");
        assertEquals(Set.of(models.get(1), models.get(2), models.get(3)), schema.modelsReached(p));
        assertTrue(!closure.contains(p), "p must not appear in its own closure");
    }

    @Test
    void linksFromReturnsEmptyForAPropertyWithNoOutgoingLinks() {
        List<Model> models = createModels(3, 1);
        CprSchema schema = new CprSchemaBuilder(models, CprTopologyMode.HOTSPOT, 0.0, 1.0, 1, 1L, null, false).build();

        Property p = models.get(0).properties().get(0);
        assertEquals(List.of(), schema.linksFrom(p));
        assertEquals(Set.of(), schema.closure(p));
    }

    @Test
    void linkedPropertyCountAndLinkCountMatchAsGraph() {
        List<Model> models = createModels(3, 4);
        CprSchema schema = new CprSchemaBuilder(models, CprTopologyMode.UNIFORM, 0.5, 2.0, 3, 55L, null, false).build();

        assertEquals(schema.asGraph().size(), schema.linkedPropertyCount());
        int totalLinks = schema.asGraph().values().stream().mapToInt(List::size).sum();
        assertEquals(totalLinks, schema.linkCount());
    }

    private static List<Model> createModels(int modelCount, int propertiesPerModel) {
        return new ModelFactory(1).createModels(Collections.nCopies(modelCount, propertiesPerModel));
    }
}
