package de.tum.tssimulator.bookkeeping;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import de.tum.tssimulator.core.Model;
import de.tum.tssimulator.core.ModelFactory;
import de.tum.tssimulator.core.Resource;
import de.tum.tssimulator.core.Transaction;
import de.tum.tssimulator.core.TransactionGroup;
import de.tum.tssimulator.monitoring.SimulationEventSink;
import de.tum.tssimulator.monitoring.SimulationLogger;
import de.tum.tssimulator.state.DirtyKey;
import de.tum.tssimulator.state.DirtyStateTracker;
import de.tum.tssimulator.state.TransactionGroupRegistry;

/**
 * V3's {@code collapsed_fraction}, whose definition was pinned in V3's plan before this
 * counter existed.
 *
 * <pre>
 *   collapsed_fraction = collapseSkipped / collapseOffered
 * </pre>
 *
 * <p><b>Both cases below are required, and the second is the one that matters.</b> The pipeline runs
 * two independent filters - {@code filterAlreadyCoveredResources} (visited set, cascade pruning) and
 * {@code filterBookkeepers} (dirty set, fan-out collapse) - and {@code attemptedBookkeepersTotal} is
 * incremented before BOTH. A counter "simplified" to {@code attempted - created} would therefore put
 * cascade pruning in the denominator, a mechanism PostgreSQL has no counterpart for, and case (a)
 * alone would still pass. Case (b) is what makes the implementation rule enforceable rather than
 * documentary.
 */
class CollapsedFractionCounterTest {

    @Test
    @DisplayName("(a) a dirty collision lands in BOTH numerator and denominator")
    void dirtyCollisionCountsInNumeratorAndDenominator() {
        Fixture f = new Fixture();

        // Two producers in DIFFERENT groups both target res3. The visited set is per-group, so it
        // drops neither; the dirty set is shared, so the second is a genuine fan-out collapse -
        // which is exactly what an outbox's unique partial index rejects.
        f.createTargeting("P1", f.res3);
        f.createTargeting("P2", f.res3);

        assertEquals(2, f.offered(), "both CUs were offered to the collapse");
        assertEquals(1, f.skipped(), "the second found its target already dirty");
        assertEquals(0, f.visitedSetDropped(), "no cascade pruning in this case");
        assertEquals(0.5, f.collapsedFraction(), 1e-9);
    }

    @Test
    @DisplayName("(b) a visited-set drop lands in NEITHER - the denominator must not move")
    void visitedSetDropIsExcludedFromBothTerms() {
        Fixture f = new Fixture();

        // The parent already holds res3, so its CU aimed at res3 is dropped by the visited set
        // BEFORE filterBookkeepers ever sees it. That is cascade pruning, not collapse.
        f.createTargetingWithParentHolding("P1", f.res3, f.res3);

        assertEquals(1, f.visitedSetDropped(), "the CU was pruned by the group's visited set");
        assertEquals(0, f.offered(),
                "a pruned CU was never offered to the collapse - if this is non-zero the counter is "
                + "being derived from a total counted before the visited-set filter");
        assertEquals(0, f.skipped(), "and it is not a collapse skip");
    }

    @Test
    @DisplayName("(a) and (b) together: pruning does not dilute the fraction")
    void pruningDoesNotChangeTheFraction() {
        Fixture f = new Fixture();

        f.createTargeting("P1", f.res3);
        f.createTargeting("P2", f.res3);          // -> offered 2, skipped 1
        f.createTargetingWithParentHolding("P3", f.res4, f.res4);  // -> pruned, counted nowhere

        assertEquals(1, f.visitedSetDropped());
        assertEquals(0.5, f.collapsedFraction(), 1e-9,
                "the pruned CU must not appear in the denominator - a fraction derived from "
                + "attemptedBookkeepersTotal would read 1/3 here, not 1/2");
    }

    // ---------------------------------------------------------------------------------------

    private static final class Fixture {
        private final Model parentModel;
        private final Model targetModel;
        final Resource res3;
        final Resource res4;
        private final SimulationLogger logger = new SimulationLogger(new NoOpSink(), 0, 32);
        private final TransactionGroupRegistry registry = new TransactionGroupRegistry();
        private final Set<DirtyKey> dirty = new HashSet<>();

        Fixture() {
            List<Model> pool = new ModelFactory(2).createModels(List.of(1, 1));
            this.parentModel = pool.get(0);
            this.targetModel = pool.get(1);
            this.res3 = targetModel.getOwnedResources().get(0);
            this.res4 = targetModel.getOwnedResources().get(1);
        }

        /** A parent holding one of ITS OWN resources, emitting one CU at {@code target}. */
        void createTargeting(String id, Resource target) {
            Resource own = parentModel.getOwnedResources().get(0);
            run(id, List.of(own), target);
        }

        /** A parent that ALREADY HOLDS the resource its CU aims at - so the visited set prunes it. */
        void createTargetingWithParentHolding(String id, Resource held, Resource target) {
            run(id, List.of(held), target);
        }

        private void run(String id, List<Resource> parentResources, Resource target) {
            TransactionGroup group = new TransactionGroup();
            Transaction parent = new Transaction(id, parentResources, group);
            parent.initializeState();
            group.setParent(parent);

            BookkeeperFactory factory = txn -> List.of(
                    BookkeeperTransaction.createAcid(txn, List.of(target),
                            BookkeepingReason.MODEL_INCONSISTENCY, targetModel, 0));

            new BASEBookkeeperCreator(factory, logger, registry, null,
                    new SetBackedTracker(dirty), null, false, () -> 0L)
                    .createBookkeepers(parent);
        }

        int offered() {
            return logger.metrics().getCollapseOfferedTotal();
        }

        int skipped() {
            return logger.metrics().getCollapseSkippedTotal();
        }

        int visitedSetDropped() {
            return logger.metrics().getVisitedSetDroppedTotal();
        }

        double collapsedFraction() {
            return offered() == 0 ? 0.0 : skipped() / (double) offered();
        }
    }

    /** Minimal tracker: markDirty returns true only the first time a key is seen. */
    private record SetBackedTracker(Set<DirtyKey> dirty) implements DirtyStateTracker {
        @Override
        public boolean isDirty(DirtyKey key) {
            return dirty.contains(key);
        }

        @Override
        public boolean markDirty(DirtyKey key, long virtualTime) {
            return dirty.add(key);
        }

        @Override
        public void clearDirty(DirtyKey key, long virtualTime) {
            dirty.remove(key);
        }

        @Override
        public int size() {
            return dirty.size();
        }
    }

    private static final class NoOpSink implements SimulationEventSink {
        @Override
        public void writeLine(String line) {
        }

        @Override
        public void close() {
        }
    }
}
