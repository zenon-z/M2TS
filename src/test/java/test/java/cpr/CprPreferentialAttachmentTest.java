package test.java.cpr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import de.tum.tssimulator.bookkeeping.CprTopologyMode;
import de.tum.tssimulator.core.Model;
import de.tum.tssimulator.core.ModelFactory;
import de.tum.tssimulator.core.Property;
import de.tum.tssimulator.cpr.CprSchema;
import de.tum.tssimulator.cpr.CprSchemaBuilder;


/**
 * Price-style preferential attachment on in-degree ({@code alpha} = initial attractiveness), and
 * the property-level total order that makes the schema a DAG by construction.
 *
 * <p>Alpha is what lets the named topologies be replaced by a continuous axis: it concentrates the
 * target draw inside the candidate set that the model graph allows.
 *
 * <p><b>The total-order tests below assert a property of the FLAG, not of the campaign.</b> W1-W4
 * runs with {@code cprAcyclicTotalOrder} OFF (see {@code WorkloadAxisDefaults.BASE_ACYCLIC_TOTAL_ORDER}):
 * cascade termination there is the runtime visited set, which is the mechanism the paper actually
 * claims, and a structural DAG would additionally forbid the mutual-maintenance graphs a real
 * multi-model schema has. The option remains in the codebase and must keep working when enabled -
 * that is what {@code totalOrderMakesTheSchemaAcyclicUnderUniform} pins. Do not read it as a
 * statement that the campaign's schema is acyclic; it is not.
 */
class CprPreferentialAttachmentTest {

    private static final int MODELS = 10;
    private static final int PROPS = 20;
    private static final long SEED = 67890L;

    private static List<Model> pool() {
        return new ModelFactory(50).createModels(Collections.nCopies(MODELS, PROPS));
    }

    private static CprSchema build(Double alpha, boolean totalOrder) {
        return new CprSchemaBuilder(pool(), CprTopologyMode.UNIFORM, 0.25, 2.0, 5, SEED,
                alpha, totalOrder).build();
    }

    private static Map<Property, Integer> inDegrees(CprSchema schema) {
        Map<Property, Integer> in = new HashMap<>();
        for (Map.Entry<Property, List<Property>> e : schema.asGraph().entrySet()) {
            for (Property t : e.getValue()) {
                in.merge(t, 1, Integer::sum);
            }
        }
        return in;
    }

    /** Share of all edges landing on the top 5% of targets by in-degree. */
    private static double topShare(CprSchema schema) {
        List<Integer> degrees = new ArrayList<>(inDegrees(schema).values());
        degrees.sort(Collections.reverseOrder());
        int total = degrees.stream().mapToInt(Integer::intValue).sum();
        if (total == 0) {
            return 0.0;
        }
        int top = Math.max(1, (int) Math.round(MODELS * PROPS * 0.05));
        int hot = degrees.stream().limit(top).mapToInt(Integer::intValue).sum();
        return hot / (double) total;
    }

    /**
     * alpha = null must take the uniform draw, so every existing experiment is untouched by the
     * knob's existence.
     *
     * <p><b>This test previously compared the 6-arg {@code CprSchemaBuilder} overload against an
     * explicit {@code (null, false)}.</b> That overload has been deleted - it defaulted
     * {@code alpha = null, acyclicTotalOrder = false} and those defaults went silently wrong for
     * four call sites when the design moved, including the one that fed {@code closure_size} into
     * every {@code RawRunRow}. With it gone the old assertion compared a value to itself, so it is
     * replaced rather than kept green.
     *
     * <p>What remains testable is the property that actually matters: the uniform path must be
     * reached by {@code alpha = null} and must NOT be what a concentrating alpha produces. A
     * tautology in its place would be worse than no test.
     */
    @Test
    void nullAlphaTakesTheUniformDrawAndDiffersFromAConcentratingAlpha() {
        CprSchema uniform = build(null, false);
        assertEquals(uniform.asGraph(), build(null, false).asGraph(),
                "alpha=null must be deterministic at a fixed seed");
        assertNotEquals(uniform.asGraph(), build(0.05, false).asGraph(),
                "a concentrating alpha must not reproduce the uniform draw - if it does, the "
                        + "preferential path is not being taken and Panel D has no axis");
    }

    /** Small alpha concentrates in-degree; large alpha converges back to uniform. */
    @Test
    void smallAlphaConcentratesInDegreeAndLargeAlphaDoesNot() {
        double concentrated = topShare(build(0.05, false));
        double diffuse = topShare(build(1000.0, false));
        double uniform = topShare(build(null, false));

        assertTrue(concentrated > diffuse * 1.5,
                "alpha=0.05 must concentrate in-degree materially more than alpha=1000 (got "
                        + concentrated + " vs " + diffuse + ")");
        assertTrue(Math.abs(diffuse - uniform) < 0.05,
                "large alpha must converge back to the uniform draw (got " + diffuse
                        + " vs uniform " + uniform + ")");
    }

    /**
     * alpha = 0 is rejected rather than silently producing nothing: every in-degree starts at 0,
     * so a purely multiplicative rule can never select a first target. That is the reason the
     * additive constant exists.
     */
    @Test
    void zeroAlphaIsRejected() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> build(0.0, false));
        assertTrue(e.getMessage().contains("initial attractiveness"), e.getMessage());
    }

    /** No source may link to the same target twice, on either draw path. */
    @Test
    void targetsAreDistinctPerSource() {
        for (Double alpha : new Double[] {null, 0.05, 5.0}) {
            CprSchema schema = build(alpha, false);
            for (Map.Entry<Property, List<Property>> e : schema.asGraph().entrySet()) {
                Set<Property> seen = new HashSet<>(e.getValue());
                assertEquals(e.getValue().size(), seen.size(),
                        "duplicate target for one source at alpha=" + alpha);
            }
        }
    }

    /**
     * The total order makes the schema acyclic even under UNIFORM, which is cyclic at the MODEL
     * level - that is the whole point of reinstating it. Checked by walking the transitive closure
     * for a self-reachable property.
     */
    @Test
    void totalOrderMakesTheSchemaAcyclicUnderUniform() {
        CprSchema acyclic = build(0.05, true);
        for (Property p : acyclic.asGraph().keySet()) {
            assertTrue(!acyclic.closure(p).contains(p),
                    "property " + p + " reaches itself: the total order did not produce a DAG");
        }
    }

    /** ...and the ordering constraint is what does it - every edge goes strictly "forward". */
    @Test
    void totalOrderStillProducesEdgesAndCrossesModels() {
        CprSchema acyclic = build(0.05, true);
        int edges = acyclic.linkCount();
        assertTrue(edges > 0, "the total order must not eliminate every edge");
        for (Map.Entry<Property, List<Property>> e : acyclic.asGraph().entrySet()) {
            for (Property t : e.getValue()) {
                assertTrue(!t.model().equals(e.getKey().model()),
                        "CPR edges must remain cross-model under the total order");
            }
        }
    }
}
