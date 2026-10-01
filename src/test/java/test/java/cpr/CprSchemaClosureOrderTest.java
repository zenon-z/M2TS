package test.java.cpr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.junit.jupiter.api.Test;

import de.tum.tssimulator.bookkeeping.CprTopologyMode;
import de.tum.tssimulator.core.Model;
import de.tum.tssimulator.core.ModelFactory;
import de.tum.tssimulator.core.Property;
import de.tum.tssimulator.cpr.CprSchema;
import de.tum.tssimulator.cpr.CprSchemaBuilder;

/**
 * {@link CprSchema#closure(Property)} must iterate in a canonical, reproducible order.
 *
 * <p>It did not. The closures were built with {@code Set.copyOf(visited)}, and {@code Set.copyOf}
 * returns an {@code ImmutableCollections} set whose iteration order is deliberately randomized per
 * JVM by a SALT seeded from {@code System.nanoTime()} at class-initialization. The JDK documents
 * this order as unspecified; in practice it differs on every run.
 *
 * <p>That made the S2PL D-oracle arm irreproducible.
 * {@code TransactionFactory.expandToOracleClosure()} walks this set to build a transaction's
 * resource list, and {@code TransactionLockCoordinator.acquireAllLocks()} acquires locks in list
 * order - so the same seed produced a different lock-acquisition sequence, different contention,
 * and throughput spreading ~12% run to run (8.7-10.0 at k=25/N=4) while the static, dynamic and
 * C2PL arms were bit-identical. Paired-seed comparison between the oracle and dynamic arms is the
 * basis of that arm's headline claim, so an arm that will not reproduce undermines the method, not
 * just the number.
 *
 * <p>Note what a weaker test would miss: closure *contents* were always correct and closure *sizes*
 * were always identical. Only the order moved. A test asserting set equality passes on the broken
 * version.
 */
class CprSchemaClosureOrderTest {

    private static List<String> closureOrderFingerprint(CprSchema schema, List<Model> pool) {
        List<String> fingerprint = new ArrayList<>();
        for (Model m : pool) {
            for (Property p : m.properties()) {
                List<String> targets = new ArrayList<>();
                for (Property t : schema.closure(p)) {
                    targets.add(t.model().getName() + "." + t.name());
                }
                fingerprint.add(p.model().getName() + "." + p.name() + " -> " + targets);
            }
        }
        return fingerprint;
    }

    /**
     * Two schemas built identically must expose closures in the same ORDER, not merely with the
     * same members. Within one JVM this is a weaker check than the cross-JVM case that actually
     * failed (one SALT per JVM, so both instances here share it), so it is paired with the
     * canonical-ordering assertion below, which fails on the broken version regardless of SALT.
     */
    @Test
    void closureOrderIsStableAcrossSchemaInstances() {
        List<Model> poolA = new ModelFactory(50).createModels(List.of(4, 4, 4, 4));
        List<Model> poolB = new ModelFactory(50).createModels(List.of(4, 4, 4, 4));
        CprSchema a = new CprSchemaBuilder(poolA, CprTopologyMode.UNIFORM, 0.9, 2.0, 4, 1000003L, null, false).build();
        CprSchema b = new CprSchemaBuilder(poolB, CprTopologyMode.UNIFORM, 0.9, 2.0, 4, 1000003L, null, false).build();

        assertEquals(closureOrderFingerprint(a, poolA), closureOrderFingerprint(b, poolB),
                "same seed and shape must give the same closure iteration order");
    }

    /**
     * The real guard, and the one that is SALT-independent: every closure must come out sorted by
     * (model name, property index, property name). A canonical order is reproducible by
     * construction, so this holds across JV without needing to fork one.
     */
    @Test
    void closuresAreCanonicallyOrdered() {
        List<Model> pool = new ModelFactory(50).createModels(List.of(4, 4, 4, 4));
        CprSchema schema = new CprSchemaBuilder(pool, CprTopologyMode.UNIFORM, 0.9, 2.0, 4, 1000003L, null, false).build();

        boolean sawMultiElementClosure = false;
        for (Model m : pool) {
            for (Property p : m.properties()) {
                List<Property> actual = new ArrayList<>(schema.closure(p));
                if (actual.size() > 1) {
                    sawMultiElementClosure = true;
                }
                List<Property> expected = new ArrayList<>(actual);
                expected.sort(Comparator
                        .comparing((Property q) -> q.model().getName())
                        .thenComparing(Property::index)
                        .thenComparing(Property::name));
                assertEquals(describe(expected), describe(actual),
                        "closure of " + p + " is not in canonical (model, index, name) order");
            }
        }
        assertTrue(sawMultiElementClosure,
                "fixture produced no closure with >1 element - the test cannot observe ordering at all");
    }

    /** Guards the premise of the test above: a schema where every closure is empty would pass it
     * vacuously, exactly the way the copyBuilder round-trip test passed vacuously for an unset
     * field. */
    @Test
    void fixtureActuallyProducesClosures() {
        List<Model> pool = new ModelFactory(50).createModels(List.of(4, 4, 4, 4));
        CprSchema schema = new CprSchemaBuilder(pool, CprTopologyMode.UNIFORM, 0.9, 2.0, 4, 1000003L, null, false).build();
        int total = 0;
        for (Model m : pool) {
            for (Property p : m.properties()) {
                total += schema.closure(p).size();
            }
        }
        assertFalse(total == 0, "fixture built a schema with no CPR links at all");
    }

    private static List<String> describe(List<Property> ps) {
        List<String> out = new ArrayList<>(ps.size());
        for (Property p : ps) {
            out.add(p.model().getName() + "." + p.name());
        }
        return out;
    }
}
