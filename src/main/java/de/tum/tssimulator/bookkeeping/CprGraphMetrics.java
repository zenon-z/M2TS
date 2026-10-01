package de.tum.tssimulator.bookkeeping;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import de.tum.tssimulator.cpr.CprSchema;

/**
 * Static shape metrics for the generated CPR relation.
 */
public record CprGraphMetrics(
        int sources,
        int edges,
        double meanCprsPerActiveSource,
        double meanTransitiveReachableResources,
        int maxTransitiveReachableResources,
        double meanInDegree,
        int maxInDegree,
        double inDegreeGini,
        int inDegreeNonzeroResources) {

    public static CprGraphMetrics from(CprSchema schema, int totalProperties) {
        return from(schema.asGraph(), totalProperties);
    }

    // Generic over the graph's key/node type: the algorithm never touches resource content,
    // only identity/equality, so it works identically whether nodes are Strings (as in
    // CprGraphMetricsTest's isolated graph fixtures) or Resources (the real call path).
    static <T> CprGraphMetrics from(Map<T, List<T>> graph, int totalResources) {
        int sources = graph.size();
        int edges = graph.values().stream().mapToInt(List::size).sum();
        double meanCprs = sources == 0 ? 0.0 : edges / (double) sources;

        Map<T, Integer> reachableMemo = new HashMap<>();
        int reachableSum = 0;
        int reachableMax = 0;
        for (T source : graph.keySet()) {
            int reachable = reachableResources(source, graph, reachableMemo);
            reachableSum += reachable;
            reachableMax = Math.max(reachableMax, reachable);
        }

        Map<T, Integer> inDegrees = new HashMap<>();
        for (Map.Entry<T, List<T>> entry : graph.entrySet()) {
            inDegrees.putIfAbsent(entry.getKey(), 0);
            for (T target : entry.getValue()) {
                inDegrees.merge(target, 1, Integer::sum);
            }
        }

        List<Integer> degreeValues = new ArrayList<>();
        int nonzero = 0;
        int maxInDegree = 0;
        int inDegreeSum = 0;
        for (int degree : inDegrees.values()) {
            if (degree > 0) {
                nonzero++;
            }
            maxInDegree = Math.max(maxInDegree, degree);
            inDegreeSum += degree;
            degreeValues.add(degree);
        }
        int zeros = Math.max(0, totalResources - degreeValues.size());
        for (int i = 0; i < zeros; i++) {
            degreeValues.add(0);
        }

        return new CprGraphMetrics(
                sources,
                edges,
                meanCprs,
                sources == 0 ? 0.0 : reachableSum / (double) sources,
                reachableMax,
                totalResources == 0 ? 0.0 : inDegreeSum / (double) totalResources,
                maxInDegree,
                gini(degreeValues),
                nonzero);
    }

    private static <T> int reachableResources(T source, Map<T, List<T>> graph,
            Map<T, Integer> memo) {
        Integer memoized = memo.get(source);
        if (memoized != null) {
            return memoized;
        }
        Set<T> reachable = new HashSet<>();
        collectReachable(source, graph, reachable);
        int count = reachable.size();
        memo.put(source, count);
        return count;
    }

    private static <T> void collectReachable(T source, Map<T, List<T>> graph,
            Set<T> reachable) {
        for (T target : graph.getOrDefault(source, List.of())) {
            if (reachable.add(target)) {
                collectReachable(target, graph, reachable);
            }
        }
    }

    private static double gini(List<Integer> values) {
        int n = values.size();
        if (n == 0) {
            return 0.0;
        }
        values.sort(Integer::compareTo);
        long sum = 0;
        long weightedSum = 0;
        for (int i = 0; i < n; i++) {
            int value = values.get(i);
            sum += value;
            weightedSum += (long) (i + 1) * value;
        }
        if (sum == 0) {
            return 0.0;
        }
        return (2.0 * weightedSum) / (n * (double) sum) - (n + 1.0) / n;
    }
}
