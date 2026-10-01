package de.tum.tssimulator.cpr;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Stream;

import org.apache.commons.math3.distribution.BinomialDistribution;
import org.apache.commons.math3.random.Well19937c;

import de.tum.tssimulator.bookkeeping.CprTopologyMode;
import de.tum.tssimulator.core.Model;
import de.tum.tssimulator.core.Property;

/**
 * Builds a {@link CprSchema} in two stages.
 *
 * <p>Stage 1 (model graph) is a pure, fully deterministic function of {@code (models, topology)}
 * - no {@link Random} involved - and determines which models may link to which.
 *
 * <p>Stage 2 (property links) draws the actual {@link PropertyLink}s: {@code semanticOverlap}
 * selects a global fraction of the full property pool as emitting sources (not per-model), and
 * {@code avgCprsPerResource}/{@code maxCprsPerResource} (mu/m) control each selected source's
 * out-degree via the same binomial draw {@code SimpleCPRFactory} used.
 */
public final class CprSchemaBuilder {
    private final List<Model> models;
    private final CprTopologyMode topology;
    private final double semanticOverlap;
    private final double mu; // avgCprsPerResource
    private final int m; // maxCprsPerResource
    private final Long cprSeed;
    private final Double initialAttractiveness;
    private final boolean acyclicTotalOrder;

    /**
     * @param initialAttractiveness Price-style preferential attachment on IN-DEGREE: a target is
     *        drawn with probability proportional to {@code (inDegree + alpha)}. {@code null} means
     *        the historical UNIFORM target draw, bit-identical. Large alpha swamps the in-degree
     *        term and converges back to uniform; small alpha lets already-targeted properties keep
     *        being chosen, so hotspots emerge. Must be {@code > 0} when set - at exactly 0 every
     *        weight starts at 0 and nothing can ever be drawn, which is the reason Dorogovtsev,
     *        Mendes and Samukhin introduced the constant (initial attractiveness) at all.
     *
     *        <p>This is a STAGE-2 knob. It does not replace {@link CprTopologyMode}, which is the
     *        Stage-1 model-level digraph - alpha concentrates the target draw WITHIN whatever
     *        candidate set the model graph allows. Using alpha in place of the named topologies
     *        therefore means running {@code UNIFORM} (complete cross-model digraph) and letting
     *        alpha do the concentrating.
     * @param acyclicTotalOrder when true, links go only from lower- to higher-ordered properties
     *        under a seeded total order, making the schema a DAG BY CONSTRUCTION regardless of the
     *        model graph. This restores the property-level ordering {@code SimpleCPRFactory} had
     *        and that was deleted with it; it is what makes {@code UNIFORM} usable as the single
     *        model graph, since UNIFORM is cyclic at the model level and cascade depth would
     *        otherwise be bounded only by the runtime visited set rather than being well defined.
     */
    public CprSchemaBuilder(List<Model> models, CprTopologyMode topology, double semanticOverlap,
            double mu, int m, Long cprSeed, Double initialAttractiveness, boolean acyclicTotalOrder) {
        this.models = models;
        this.topology = topology;
        this.semanticOverlap = semanticOverlap;
        this.mu = mu;
        this.m = m;
        this.cprSeed = cprSeed;
        this.initialAttractiveness = initialAttractiveness;
        this.acyclicTotalOrder = acyclicTotalOrder;
    }

    public CprSchema build() {
        validateParameters();
        Map<Model, List<Model>> modelGraph = buildModelGraph(models, topology);
        Map<Property, List<PropertyLink>> outgoing = buildPropertyLinks(modelGraph);
        return new CprSchema(outgoing);
    }

    private void validateParameters() {
        if (semanticOverlap < 0.0 || semanticOverlap > 1.0) {
            throw new IllegalArgumentException("semanticOverlap must be between 0 and 1");
        }
        if (m < 1) {
            throw new IllegalArgumentException("maxCprsPerResource must be at least 1");
        }
        if (mu < 1.0 || mu > m) {
            throw new IllegalArgumentException("avgCprsPerResource must be between 1 and maxCprsPerResource");
        }
        if (initialAttractiveness != null && !(initialAttractiveness > 0.0)) {
            throw new IllegalArgumentException(
                    "cprInitialAttractiveness must be > 0 (got " + initialAttractiveness + "): every "
                            + "in-degree starts at 0, so alpha = 0 leaves every candidate at weight 0 "
                            + "and no target can ever be drawn. That is precisely what the additive "
                            + "constant exists to prevent - Dorogovtsev/Mendes/Samukhin call it "
                            + "initial attractiveness.");
        }
    }

    /**
     * Stage 1: pure, fully deterministic function of {@code (models, topology)} - no
     * {@link Random} for any topology. Runs once per {@link #build()} call, over the
     * order-stable list {@code ModelFactory.createModels} returns.
     */
    static Map<Model, List<Model>> buildModelGraph(List<Model> models, CprTopologyMode topology) {
        Map<Model, List<Model>> edges = new LinkedHashMap<>();
        models.forEach(mdl -> edges.put(mdl, new ArrayList<>()));
        int n = models.size();
        if (n < 2) {
            return edges; // nothing cross-model is possible
        }

        switch (topology) {
            case LINE -> {
                for (int i = 0; i < n - 1; i++) {
                    edges.get(models.get(i)).add(models.get(i + 1));
                }
            }
            case RING -> {
                for (int i = 0; i < n - 1; i++) {
                    edges.get(models.get(i)).add(models.get(i + 1));
                }
                edges.get(models.get(n - 1)).add(models.get(0));
            }
            case FAN_OUT -> {
                // Bidirectional: hub out-degree n-1, every leaf out-degree 1 back to the hub.
                // Deliberately not a one-directional star like HOTSPOT - see PropertyLink/the
                // CPR-kind out-of-scope note (bijective/projective/aggregate) for why the two
                // stars differ: an aggregate (HOTSPOT) can't be inverted, a bijective
                // correspondence (FAN_OUT) could in principle be walked either way.
                Model hub = models.get(0);
                for (int i = 1; i < n; i++) {
                    edges.get(hub).add(models.get(i));
                    edges.get(models.get(i)).add(hub);
                }
            }
            case HOTSPOT -> {
                Model hub = models.get(0);
                for (int i = 1; i < n; i++) {
                    edges.get(models.get(i)).add(hub);
                }
            }
            case UNIFORM -> {
                // Complete cross-model digraph: every model links to every other model. The
                // no-bias control HOTSPOT/FAN_OUT deviate from - not a topology of its own.
                for (Model a : models) {
                    for (Model b : models) {
                        if (!a.equals(b)) {
                            edges.get(a).add(b);
                        }
                    }
                }
            }
        }
        return edges;
    }

    /**
     * Stage 2: selection is global, over all properties in the model pool - not per source
     * model. {@code semanticOverlap} is "fraction of properties with at least one outgoing
     * link" against the full pool, not "fraction of each emitting model's properties".
     */
    private Map<Property, List<PropertyLink>> buildPropertyLinks(Map<Model, List<Model>> modelGraph) {
        Map<Property, List<PropertyLink>> outgoing = new HashMap<>();
        // Drives global selection + per-property binomial/target draws - mirrors
        // SimpleCPRFactory's one seeded Random.
        Random selectionRandom = (cprSeed != null) ? new Random(cprSeed) : new Random(System.nanoTime());

        // Seeded total order over every property, used only when acyclicTotalOrder is on. Drawn
        // from the SAME stream as everything else in Stage 2, so the schema stays a pure function
        // of cprSeed.
        Map<Property, Integer> order = new HashMap<>();
        if (acyclicTotalOrder) {
            List<Property> all = new ArrayList<>(models.stream()
                    .flatMap(mdl -> mdl.properties().stream()).toList());
            Collections.shuffle(all, selectionRandom);
            for (int i = 0; i < all.size(); i++) {
                order.put(all.get(i), i);
            }
        }

        // A property may emit only if it has at least one REACHABLE target. Under the total order
        // that also excludes properties at the tail of the order, which would otherwise be selected
        // as sources and contribute zero edges - dragging realised mu below nominal silently
        // rather than failing.
        List<Property> eligiblePool = models.stream()
                .filter(mdl -> !modelGraph.get(mdl).isEmpty()) // only models with somewhere to link to
                .flatMap(mdl -> mdl.properties().stream())
                .filter(p -> !candidateTargets(modelGraph, order, p).isEmpty())
                .toList();
        int totalProperties = models.stream().mapToInt(Model::propertyCount).sum();
        int activeCount = (int) Math.round(totalProperties * semanticOverlap); // against the FULL pool

        if (activeCount > eligiblePool.size()) {
            double ceiling = totalProperties == 0 ? 0.0 : eligiblePool.size() / (double) totalProperties;
            throw new IllegalArgumentException(
                    "semanticOverlap=" + semanticOverlap + " requires " + activeCount
                            + " linked properties, but topology " + topology + " allows at most "
                            + eligiblePool.size() + " of " + totalProperties + " properties to emit CPRs "
                            + "(max feasible semanticOverlap ≈ " + ceiling + " at this model count)");
        }

        List<Property> shuffled = new ArrayList<>(eligiblePool);
        Collections.shuffle(shuffled, selectionRandom);
        List<Property> selected = shuffled.subList(0, activeCount);

        // In-degree accumulated ACROSS the whole build, so preferential attachment is cumulative -
        // a property targeted early is more attractive to every later source. Resetting it per
        // source would make alpha a per-source tie-break and produce no hotspots at all.
        Map<Property, Integer> inDegree = new HashMap<>();

        for (Property p : selected) {
            List<Property> candidateTargets = candidateTargets(modelGraph, order, p);

            int upperBound = Math.min(m, candidateTargets.size());
            int k = 1;
            if (upperBound > 1) { // m=1 guard, verbatim from SimpleCPRFactory
                double q = (mu - 1.0) / (m - 1.0);
                k += sampleBinomial(upperBound - 1, q, selectionRandom);
            }
            List<Property> chosen = initialAttractiveness == null
                    ? uniformTargets(candidateTargets, k, selectionRandom)
                    : preferentialTargets(candidateTargets, k, inDegree, selectionRandom);
            for (Property t : chosen) {
                // Cross-model is structurally enforced by PropertyLink's compact constructor:
                // candidateTargets never includes p.model() by Stage 1's construction.
                outgoing.computeIfAbsent(p, ignored -> new ArrayList<>()).add(new PropertyLink(p, t));
                inDegree.merge(t, 1, Integer::sum);
            }
        }
        return outgoing;
    }

    /**
     * Targets reachable from {@code p}: cross-model by Stage 1, and - when the total order is on -
     * strictly later in that order, which is what makes the schema acyclic by construction.
     */
    private List<Property> candidateTargets(Map<Model, List<Model>> modelGraph,
            Map<Property, Integer> order, Property p) {
        Stream<Property> targets = modelGraph.get(p.model()).stream()
                .flatMap(mdl -> mdl.properties().stream());
        if (acyclicTotalOrder) {
            int from = order.get(p);
            targets = targets.filter(t -> order.get(t) > from);
        }
        return targets.toList();
    }

    /** The historical draw: shuffle, take the first k. Kept exactly, so alpha=null is unchanged. */
    private static List<Property> uniformTargets(List<Property> candidates, int k, Random random) {
        List<Property> shuffled = new ArrayList<>(candidates);
        Collections.shuffle(shuffled, random);
        return shuffled.subList(0, Math.min(k, shuffled.size()));
    }

    /**
     * Price-style preferential attachment on in-degree, WITHOUT replacement: each of the k targets
     * is drawn with probability proportional to {@code (inDegree + alpha)} and then removed from
     * the pool, so one source never links to the same target twice - the duplicate-free invariant
     * the uniform path got for free by taking a prefix of a shuffle.
     */
    private List<Property> preferentialTargets(List<Property> candidates, int k,
            Map<Property, Integer> inDegree, Random random) {
        List<Property> pool = new ArrayList<>(candidates);
        List<Property> chosen = new ArrayList<>(Math.min(k, pool.size()));
        double alpha = initialAttractiveness;
        while (chosen.size() < k && !pool.isEmpty()) {
            double total = 0.0;
            for (Property c : pool) {
                total += inDegree.getOrDefault(c, 0) + alpha;
            }
            double r = random.nextDouble() * total;
            int picked = pool.size() - 1; // guards against FP drift leaving r just above the sum
            double acc = 0.0;
            for (int i = 0; i < pool.size(); i++) {
                acc += inDegree.getOrDefault(pool.get(i), 0) + alpha;
                if (r < acc) {
                    picked = i;
                    break;
                }
            }
            chosen.add(pool.remove(picked));
        }
        return chosen;
    }

    private static int sampleBinomial(int trials, double probability, Random random) {
        return new BinomialDistribution(new Well19937c(random.nextLong()), trials, probability).sample();
    }
}
