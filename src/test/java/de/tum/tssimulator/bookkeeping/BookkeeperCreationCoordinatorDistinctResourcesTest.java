package de.tum.tssimulator.bookkeeping;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import de.tum.tssimulator.config.ConcurrencyModel;
import de.tum.tssimulator.core.Model;
import de.tum.tssimulator.core.ModelFactory;
import de.tum.tssimulator.core.Property;
import de.tum.tssimulator.core.Resource;
import de.tum.tssimulator.core.Transaction;
import de.tum.tssimulator.core.TransactionGroup;
import de.tum.tssimulator.core.program.Operation;
import de.tum.tssimulator.core.program.OperationType;
import de.tum.tssimulator.cpr.CprSchema;
import de.tum.tssimulator.cpr.CprSchemaBuilder;
import de.tum.tssimulator.monitoring.SimulationEventSink;
import de.tum.tssimulator.monitoring.SimulationLogger;
import de.tum.tssimulator.state.TransactionGroupRegistry;

class BookkeeperCreationCoordinatorDistinctResourcesTest {

    @Test
    void skipsTargetsAlreadyCoveredByParentAndDuplicateTargetsInSameBatch() {
        // Both models must come from the same ModelFactory pool - otherwise they'd both be
        // independently named "Model1" and collide under Model's name-based equality.
        List<Model> pool = new ModelFactory(2).createModels(List.of(1, 1));
        Model parentModel = pool.get(0);
        Model targetModel = pool.get(1);
        Resource res1 = parentModel.getOwnedResources().get(0);
        Resource res2 = parentModel.getOwnedResources().get(1);
        Resource res3 = targetModel.getOwnedResources().get(0);
        Resource res4 = targetModel.getOwnedResources().get(1);

        TransactionGroup group = new TransactionGroup();
        Transaction parent = transaction("T1", List.of(parentModel), List.of(res1, res2), group);

        BookkeeperFactory factory = txn -> List.of(
                bookkeeper(txn, res2, parentModel, 0),
                bookkeeper(txn, res3, targetModel, 1),
                bookkeeper(txn, res3, targetModel, 2),
                bookkeeper(txn, res4, targetModel, 3));

        TestCoordinator coordinator = new TestCoordinator(factory, logger(), new TransactionGroupRegistry());

        List<BookkeeperTransaction> created = coordinator.createBookkeepers(parent);

        assertEquals(List.of(res3, res4),
                created.stream().map(bk -> bk.getResources().get(0)).toList());
        assertEquals(2, group.getBookkeepers().size());
    }

    @Test
    void skipsCycleBackIntoAlreadyCoveredCascadeResource() {
        // Both models must come from the same ModelFactory pool - see comment above.
        List<Model> pool = new ModelFactory(2).createModels(List.of(1, 1));
        Model rootModel = pool.get(0);
        Model targetModel = pool.get(1);
        Resource res1 = rootModel.getOwnedResources().get(0);
        Resource res2 = targetModel.getOwnedResources().get(0);
        Resource res3 = targetModel.getOwnedResources().get(1);

        TransactionGroup group = new TransactionGroup();
        Transaction parent = transaction("T1", List.of(rootModel), List.of(res1), group);

        BookkeeperFactory factory = txn -> {
            Resource source = txn.getResources().get(0);
            if (res1.equals(source)) {
                return List.of(bookkeeper(txn, res2, targetModel, 0));
            }
            if (res2.equals(source)) {
                return List.of(
                        bookkeeper(txn, res1, rootModel, 1),
                        bookkeeper(txn, res2, targetModel, 2),
                        bookkeeper(txn, res3, targetModel, 3));
            }
            return List.of();
        };

        TestCoordinator coordinator = new TestCoordinator(factory, logger(), new TransactionGroupRegistry());

        List<BookkeeperTransaction> firstWave = coordinator.createBookkeepers(parent);
        List<BookkeeperTransaction> secondWave = coordinator.createBookkeepers(firstWave.get(0));

        assertEquals(List.of(res2),
                firstWave.stream().map(bk -> bk.getResources().get(0)).toList());
        assertEquals(List.of(res3),
                secondWave.stream().map(bk -> bk.getResources().get(0)).toList());
        assertEquals(List.of(res2, res3),
                group.getBookkeepers().stream().map(bk -> bk.getResources().get(0)).toList());
    }

    // Visited-set-spans-the-chain (item 8/14): with the depth check gone, cascade termination
    // is entirely the responsibility of filterAlreadyCoveredResources's covered-resources set,
    // which spans the whole cascade via the shared TransactionGroup - not just one hop. Drives
    // a real CprSchema/SchemaCprFactory (not a synthetic BookkeeperFactory) around a RING
    // topology, whose cycle-back-to-root is exactly the case a depth check used to guard.
    @Test
    void visitedSetSpansEntireRingCascadeAndTerminates() {
        List<Model> models = new ModelFactory(50).createModels(List.of(1, 1, 1, 1));
        CprSchema schema = new CprSchemaBuilder(models, CprTopologyMode.RING, 1.0, 1.0, 1, 1L, null, false).build();
        SchemaCprFactory factory = new SchemaCprFactory(schema, ConcurrencyModel.ACID);

        Property p = models.get(0).properties().get(0);
        Resource root = new Resource(7, p);
        TransactionGroup group = new TransactionGroup();
        Transaction parent = writeTransaction("T1", List.of(root), group);

        TestCoordinator coordinator = new TestCoordinator(factory, logger(), new TransactionGroupRegistry());

        List<Transaction> frontier = new ArrayList<>(List.of(parent));
        int hops = 0;
        while (!frontier.isEmpty() && hops < 10) { // safety bound on the *test*, not the coordinator
            List<Transaction> nextFrontier = new ArrayList<>();
            for (Transaction txn : frontier) {
                nextFrontier.addAll(coordinator.createBookkeepers(txn));
            }
            frontier = nextFrontier;
            hops++;
        }

        assertTrue(hops < 10, "RING cascade should terminate well before the test's own safety bound");

        Set<Property> closure = schema.closure(p);
        Set<Resource> covered = new HashSet<>();
        for (BookkeeperTransaction bk : group.getBookkeepers()) {
            covered.addAll(bk.getResources());
        }
        assertEquals(closure.size(), covered.size(),
                "total C produced by the cascade should equal |closure(p)| for a single-write parent");
        for (Resource r : covered) {
            assertTrue(closure.contains(r.property()));
            assertEquals(7, r.instanceId(), "identity correspondence: every CU stays at the root's instance id");
        }
    }

    // maxBookkeeperDepth removal doesn't regress cascade depth (item 8/14): a LINE chain of
    // depth 4 is deeper than the old default maxBookkeeperDepth=1 ever allowed through.
    @Test
    void cascadeBeyondOldDepthOneCapNowSucceeds() {
        List<Model> models = new ModelFactory(20).createModels(List.of(1, 1, 1, 1, 1));
        // LINE's ceiling at n=5 is (n-1)/n = 0.8; at exactly the ceiling every eligible property
        // (including model0's) is selected regardless of seed, so this isn't seed-flaky.
        CprSchema schema = new CprSchemaBuilder(models, CprTopologyMode.LINE, 0.8, 1.0, 1, 3L, null, false).build();
        SchemaCprFactory factory = new SchemaCprFactory(schema, ConcurrencyModel.ACID);

        Resource root = new Resource(3, models.get(0).properties().get(0));
        TransactionGroup group = new TransactionGroup();
        Transaction parent = writeTransaction("T1", List.of(root), group);

        TestCoordinator coordinator = new TestCoordinator(factory, logger(), new TransactionGroupRegistry());

        Transaction current = parent;
        int depth = 0;
        while (depth < 10) { // safety bound on the *test*, not the coordinator
            List<BookkeeperTransaction> created = coordinator.createBookkeepers(current);
            if (created.isEmpty()) {
                break;
            }
            current = created.get(0);
            depth++;
        }

        assertEquals(4, depth, "cascade should reach depth 4 - beyond the old maxBookkeeperDepth=1 cap");
    }

    private static Transaction writeTransaction(String id, List<Resource> resources, TransactionGroup group) {
        List<Operation> operations = resources.stream().map(r -> new Operation(OperationType.WRITE, r)).toList();
        Transaction txn = new Transaction(id, 0L, resources, group, -1, operations, null);
        txn.initializeState();
        group.setParent(txn);
        return txn;
    }

    private static Transaction transaction(String id, List<Model> models, List<Resource> resources, TransactionGroup group) {
        Transaction txn = new Transaction(id, resources, group);
        txn.initializeState();
        group.setParent(txn);
        return txn;
    }

    private static BookkeeperTransaction bookkeeper(Transaction parent, Resource resource, Model targetModel, int index) {
        return BookkeeperTransaction.createAcid(parent, List.of(resource),
                BookkeepingReason.MODEL_INCONSISTENCY, targetModel, index);
    }

    private static SimulationLogger logger() {
        return new SimulationLogger(new NoOpSink(), 0, 32);
    }

    private static final class TestCoordinator extends BookkeeperCreationCoordinator {
        private TestCoordinator(BookkeeperFactory factory, SimulationLogger logger, TransactionGroupRegistry registry) {
            super(factory, logger, registry, null);
        }

        @Override
        protected List<BookkeeperTransaction> filterBookkeepers(List<BookkeeperTransaction> bookkeepers, Transaction parent) {
            return bookkeepers;
        }

        @Override
        public void onBookkeeperCompleted(BookkeeperTransaction bookkeeper) {
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
