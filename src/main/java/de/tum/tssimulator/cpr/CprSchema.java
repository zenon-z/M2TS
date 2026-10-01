package de.tum.tssimulator.cpr;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import de.tum.tssimulator.core.Model;
import de.tum.tssimulator.core.Property;

/**
 * Runtime lookup/closure structure over {@link Property}/{@link PropertyLink}: the static,
 * property-level shape produced by {@link CprSchemaBuilder}. Immutable once built - the
 * transitive closure of every property is computed eagerly in the constructor, once, rather
 * than recomputed per triggering write.
 */
public final class CprSchema {
    private final Map<Property, List<PropertyLink>> outgoing;
    private final Map<Property, Set<Property>> closureCache;

    CprSchema(Map<Property, List<PropertyLink>> outgoing) {
        this.outgoing = Map.copyOf(outgoing);
        this.closureCache = computeAllClosures(this.outgoing);
    }

    public List<PropertyLink> linksFrom(Property p) {
        return outgoing.getOrDefault(p, List.of());
    }

    public Set<Property> closure(Property p) {
        return closureCache.getOrDefault(p, Set.of());
    }

    /** Order-stable for the same reason as {@link #computeAllClosures}: {@code
     * Collectors.toUnmodifiableSet()} builds the same per-JVM salt-randomized set type as
     * {@code Set.copyOf}, so anything iterating this would be irreproducible across runs. No
     * current caller iterates it, but the collector is the hazard, not the caller - fixed here so a
     * future one cannot reintroduce the D-oracle reproducibility bug by accident. Insertion order
     * is fine here because {@code closure(p)} is now itself canonically ordered. */
    public Set<Model> modelsReached(Property p) {
        LinkedHashSet<Model> models = closure(p).stream()
                .map(Property::model)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        return Collections.unmodifiableSet(models);
    }

    public Map<Property, List<Property>> asGraph() {
        Map<Property, List<Property>> graph = new HashMap<>();
        for (Map.Entry<Property, List<PropertyLink>> e : outgoing.entrySet()) {
            graph.put(e.getKey(), e.getValue().stream().map(PropertyLink::target).toList());
        }
        return graph;
    }

    // CprSchema equivalents of SimpleCPRFactory's getConstraintMapSize()/getConstraintCount()
    public int linkedPropertyCount() {
        return outgoing.size();
    }

    public int linkCount() {
        return outgoing.values().stream().mapToInt(List::size).sum();
    }

    private static Map<Property, Set<Property>> computeAllClosures(Map<Property, List<PropertyLink>> outgoing) {
        Map<Property, Set<Property>> result = new HashMap<>();
        for (Property p : outgoing.keySet()) {
            Set<Property> visited = new HashSet<>();
            visited.add(p); // seed so a cycle back to p doesn't re-add it to its own closure
            Deque<Property> stack = new ArrayDeque<>(List.of(p));
            while (!stack.isEmpty()) {
                Property current = stack.pop();
                for (PropertyLink link : outgoing.getOrDefault(current, List.of())) {
                    if (visited.add(link.target())) {
                        stack.push(link.target());
                    }
                }
            }
            visited.remove(p); // p was only a traversal seed, not part of its own closure

            // Canonically ordered, NOT Set.copyOf(). Set.copyOf returns an ImmutableCollections
            // set whose iteration order is deliberately randomized per JVM by a SALT seeded from
            // System.nanoTime() at class-init - documented as unspecified, and genuinely different
            // on every run. That made the D-oracle arm irreproducible: expandToOracleClosure()
            // walks this set to build a transaction's resource list, and
            // TransactionLockCoordinator.acquireAllLocks() acquires in list order, so the same
            // seed produced a different lock-acquisition sequence, different contention, and
            // throughput spreading ~12% run to run (8.7-10.0 at k=25/N=4) while every other arm
            // was bit-identical. Localised by diffing an EngineEventTrace between two runs: the
            // FIRST divergent event was transaction T0-RO's 7th lock request - identical seed
            // resources, permuted closure resources.
            //
            // Sorting rather than LinkedHashSet: insertion order here is the DFS order out of a
            // HashSet, which is stable but arbitrary and would silently change if the traversal or
            // Property.hashCode ever changed. A canonical (model, property) sort is reproducible
            // by construction and independent of both.
            //
            // The dynamic arm was unaffected because its cascade reads linksFrom(), which returns
            // an ordered List - closure() has exactly one caller, and it is the oracle path.
            List<Property> ordered = new ArrayList<>(visited);
            ordered.sort(Comparator.comparing((Property q) -> q.model().getName())
                    .thenComparing(Property::index)
                    .thenComparing(Property::name));
            result.put(p, Collections.unmodifiableSet(new LinkedHashSet<>(ordered)));
        }
        return result;
    }
}
