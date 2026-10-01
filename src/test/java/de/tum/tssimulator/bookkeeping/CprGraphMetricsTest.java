package de.tum.tssimulator.bookkeeping;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class CprGraphMetricsTest {

    @Test
    void computesReachabilityDepthAndInDegreeMetrics() {
        Map<String, List<String>> graph = Map.of(
                "res1", List.of("res2", "res3"),
                "res2", List.of("res4"),
                "res3", List.of("res4"));

        CprGraphMetrics metrics = CprGraphMetrics.from(graph, 5);

        assertEquals(3, metrics.sources());
        assertEquals(4, metrics.edges());
        assertEquals(4.0 / 3.0, metrics.meanCprsPerActiveSource(), 1e-9);
        assertEquals(5.0 / 3.0, metrics.meanTransitiveReachableResources(), 1e-9);
        assertEquals(3, metrics.maxTransitiveReachableResources());
        assertEquals(0.8, metrics.meanInDegree(), 1e-9);
        assertEquals(2, metrics.maxInDegree());
        assertEquals(3, metrics.inDegreeNonzeroResources());
    }

    @Test
    void handlesEmptyGraph() {
        CprGraphMetrics metrics = CprGraphMetrics.from(Map.of(), 5);

        assertEquals(0, metrics.sources());
        assertEquals(0, metrics.edges());
        assertEquals(0.0, metrics.meanCprsPerActiveSource());
        assertEquals(0.0, metrics.meanTransitiveReachableResources());
        assertEquals(0, metrics.maxTransitiveReachableResources());
        assertEquals(0.0, metrics.meanInDegree());
        assertEquals(0, metrics.maxInDegree());
        assertEquals(0.0, metrics.inDegreeGini());
        assertEquals(0, metrics.inDegreeNonzeroResources());
    }
}
