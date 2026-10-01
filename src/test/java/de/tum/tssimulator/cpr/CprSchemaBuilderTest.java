package de.tum.tssimulator.cpr;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import de.tum.tssimulator.bookkeeping.CprTopologyMode;
import de.tum.tssimulator.core.Model;
import de.tum.tssimulator.core.ModelFactory;
import de.tum.tssimulator.core.Property;

class CprSchemaBuilderTest {

    @Test
    void deterministicSameParamsAndSeedProduceIdenticalSchema() {
        List<Model> models = createModels(4, 5);

        CprSchema first = new CprSchemaBuilder(models, CprTopologyMode.UNIFORM, 0.5, 2.5, 4, 42L, null, false).build();
        CprSchema second = new CprSchemaBuilder(models, CprTopologyMode.UNIFORM, 0.5, 2.5, 4, 42L, null, false).build();

        assertEquals(first.asGraph(), second.asGraph());
    }

    @Test
    void modelGraphShapesMatchExpectedEdgeCounts() {
        List<Model> models = createModels(4, 1);

        assertEquals(3, edgeCount(CprSchemaBuilder.buildModelGraph(models, CprTopologyMode.LINE)));
        assertEquals(4, edgeCount(CprSchemaBuilder.buildModelGraph(models, CprTopologyMode.RING)));
        assertEquals(3, edgeCount(CprSchemaBuilder.buildModelGraph(models, CprTopologyMode.HOTSPOT)));
        assertEquals(6, edgeCount(CprSchemaBuilder.buildModelGraph(models, CprTopologyMode.FAN_OUT)));
        assertEquals(12, edgeCount(CprSchemaBuilder.buildModelGraph(models, CprTopologyMode.UNIFORM)));
    }

    @Test
    void hotspotEdgesAllPointIntoTheHub() {
        List<Model> models = createModels(4, 1);
        Map<Model, List<Model>> graph = CprSchemaBuilder.buildModelGraph(models, CprTopologyMode.HOTSPOT);

        Model hub = models.get(0);
        for (int i = 1; i < models.size(); i++) {
            assertEquals(List.of(hub), graph.get(models.get(i)));
        }
        assertTrue(graph.get(hub).isEmpty());
    }

    @Test
    void degreeDistributionRespectsMeanAndMax() {
        List<Model> models = createModels(2, 200);
        double mu = 2.5;
        int m = 4;

        CprSchema schema = new CprSchemaBuilder(models, CprTopologyMode.UNIFORM, 1.0, mu, m, 7L, null, false).build();

        List<Integer> degrees = new ArrayList<>();
        for (Model model : models) {
            for (Property p : model.properties()) {
                degrees.add(schema.linksFrom(p).size());
            }
        }
        double mean = degrees.stream().mapToInt(Integer::intValue).average().orElseThrow();
        int max = degrees.stream().mapToInt(Integer::intValue).max().orElseThrow();

        assertTrue(Math.abs(mean - mu) < 0.5, "mean out-degree " + mean + " should be near mu=" + mu);
        assertTrue(max <= m, "max out-degree " + max + " should not exceed m=" + m);
    }

    @Test
    void semanticOverlapIsHonouredGlobally() {
        List<Model> models = createModels(2, 10);

        CprSchema schema = new CprSchemaBuilder(models, CprTopologyMode.UNIFORM, 0.3, 1.0, 1, 99L, null, false).build();

        assertEquals(6, schema.linkedPropertyCount());
    }

    @Test
    void semanticOverlapCeilingIsFailFastNotClamped() {
        List<Model> models = createModels(3, 1);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new CprSchemaBuilder(models, CprTopologyMode.HOTSPOT, 0.9, 1.0, 1, 1L, null, false).build());
        assertTrue(ex.getMessage().contains("semanticOverlap"));
    }

    @Test
    void everyGeneratedLinkIsCrossModel() {
        List<Model> models = createModels(4, 6);

        CprSchema schema = new CprSchemaBuilder(models, CprTopologyMode.UNIFORM, 1.0, 3.0, 5, 2024L, null, false).build();

        for (Map.Entry<Property, List<Property>> entry : schema.asGraph().entrySet()) {
            for (Property target : entry.getValue()) {
                assertTrue(!entry.getKey().model().equals(target.model()));
            }
        }
    }

    @Test
    void maxOneDoesNotDivideByZero() {
        List<Model> models = createModels(3, 2);

        CprSchema schema = assertDoesNotThrow(
                () -> new CprSchemaBuilder(models, CprTopologyMode.UNIFORM, 1.0, 1.0, 1, 5L, null, false).build());

        for (Property p : schema.asGraph().keySet()) {
            assertEquals(1, schema.linksFrom(p).size());
        }
    }

    private static int edgeCount(Map<Model, List<Model>> graph) {
        return graph.values().stream().mapToInt(List::size).sum();
    }

    // One property per model, instancePoolSize == propertiesPerModel: mirrors
    // SimpleCPRFactoryTest's flat "resN"-per-model convention, adapted to properties.
    private static List<Model> createModels(int modelCount, int propertiesPerModel) {
        return new ModelFactory(1).createModels(Collections.nCopies(modelCount, propertiesPerModel));
    }
}
