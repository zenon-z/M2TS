package test.java.bookkeeping;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import de.tum.tssimulator.bookkeeping.BookkeeperTransaction;
import de.tum.tssimulator.bookkeeping.CprTopologyMode;
import de.tum.tssimulator.bookkeeping.SchemaCprFactory;
import de.tum.tssimulator.config.ConcurrencyModel;
import de.tum.tssimulator.core.Model;
import de.tum.tssimulator.core.ModelFactory;
import de.tum.tssimulator.core.Resource;
import de.tum.tssimulator.core.Transaction;
import de.tum.tssimulator.core.TransactionGroup;
import de.tum.tssimulator.core.program.Operation;
import de.tum.tssimulator.core.program.OperationType;
import de.tum.tssimulator.cpr.CprSchema;
import de.tum.tssimulator.cpr.CprSchemaBuilder;

/**
 * {@code writeOnlyCprSources} selects exactly the WRITE resources.
 *
 * <p>The validation ladder cannot check this. Its rungs 0 and 1 run at {@code writeFraction = 1.0},
 * where every operation is a WRITE and the filter therefore selects everything - so "the flag works"
 * and "the flag is ignored" produce identical output and its bit-identity checkpoint passes either
 * way. The flag only becomes observable at rung 2, by which point several things have moved at
 * once. Same situation as the shared-lock fixes: the unit tests are the entire verification, so they
 * are written as if nothing downstream will catch a mistake.
 */
class WriteOnlyCprSourceTest {

    private static final List<Model> POOL = new ModelFactory(50).createModels(List.of(2, 2));

    private static CprSchema schema() {
        // Dense enough that every property has outgoing links, so a resource excluded from the
        // source set is excluded because of its MODE and not because it had no CPRs to begin with.
        return new CprSchemaBuilder(POOL, CprTopologyMode.UNIFORM, 1.0, 2.0, 4, 1000003L, null, false).build();
    }

    private static Resource res(int modelIndex, int propertyIndex, int instance) {
        return new Resource(instance, POOL.get(modelIndex).properties().get(propertyIndex));
    }

    /** Mixed read/write over four distinct resources - the rung-2 shape. */
    private static Transaction mixedTransaction() {
        List<Operation> ops = List.of(
                new Operation(OperationType.WRITE, res(0, 0, 1)),
                new Operation(OperationType.READ, res(0, 1, 2)),
                new Operation(OperationType.WRITE, res(1, 0, 3)),
                new Operation(OperationType.READ, res(1, 1, 4)));
        List<Resource> resources = new ArrayList<>(ops.stream().map(Operation::getResource).toList());
        Transaction t = new Transaction("T1", 0L, resources, new TransactionGroup(), -1, ops, null);
        t.initializeState();
        return t;
    }

    private static Set<Resource> sourcesOf(List<BookkeeperTransaction> created, Transaction parent) {
        // A CU's own resource is the LINK TARGET, so the source is recovered by matching instance
        // ids back against the parent's resources - the identity correspondence SchemaCprFactory
        // uses when it builds the target.
        return created.stream()
                .map(bk -> bk.getResources().get(0).instanceId())
                .distinct()
                .flatMap(instanceId -> parent.getResources().stream()
                        .filter(r -> r.instanceId() == instanceId))
                .collect(Collectors.toSet());
    }

    @Test
    void writeOnlySourcesExcludeEveryReadResource() {
        Transaction parent = mixedTransaction();
        SchemaCprFactory factory = new SchemaCprFactory(schema(), ConcurrencyModel.ACID, true, OperationType.WRITE);

        Set<Resource> sources = sourcesOf(factory.createFrom(parent), parent);

        assertEquals(Set.of(res(0, 0, 1), res(1, 0, 3)), sources,
                "exactly the WRITE resources seed CPRs");
        assertFalse(sources.contains(res(0, 1, 2)), "a READ resource must not seed a cascade");
        assertFalse(sources.contains(res(1, 1, 4)));
    }

    @Test
    void allResourcesAreSourcesWhenTheFlagIsOff() {
        Transaction parent = mixedTransaction();
        SchemaCprFactory factory = new SchemaCprFactory(schema(), ConcurrencyModel.ACID, false, OperationType.WRITE);

        Set<Resource> sources = sourcesOf(factory.createFrom(parent), parent);

        assertEquals(4, sources.size(), "with the flag off, reads seed CPRs too");
        assertTrue(sources.contains(res(0, 1, 2)));
    }

    /**
     * The premise the ladder's rungs 0 and 1 rest on, asserted rather than assumed: at
     * {@code writeFraction = 1.0} the flag selects everything, so its two settings are
     * indistinguishable and rung-0 bit-identity holds whichever way it is set.
     */
    @Test
    void atAllWriteTheFlagMakesNoDifference() {
        List<Operation> ops = List.of(
                new Operation(OperationType.WRITE, res(0, 0, 1)),
                new Operation(OperationType.WRITE, res(0, 1, 2)));
        List<Resource> resources = new ArrayList<>(ops.stream().map(Operation::getResource).toList());
        Transaction parent = new Transaction("T1", 0L, resources, new TransactionGroup(), -1, ops, null);
        parent.initializeState();

        int withFlag = new SchemaCprFactory(schema(), ConcurrencyModel.ACID, true, OperationType.WRITE)
                .createFrom(parent).size();
        int withoutFlag = new SchemaCprFactory(schema(), ConcurrencyModel.ACID, false, OperationType.WRITE)
                .createFrom(parent).size();

        assertEquals(withoutFlag, withFlag,
                "all-WRITE makes the two settings identical - which is why the ladder cannot test this flag");
        assertTrue(withFlag > 0, "and the fixture must actually produce CPRs, or this proves nothing");
    }

    /**
     * Rung 3's effect is bounded at the ROOT. CU resources are all-WRITE by construction, so every
     * CU keeps seeding regardless of the flag - only the parent's READ resources stop. The rung-3
     * delta is a narrower initial fan-out, not a halved cascade, and should not be read as one.
     */
    @Test
    void cuResourcesAreStillSourcesUnderTheFlag() {
        Transaction parent = mixedTransaction();
        SchemaCprFactory factory = new SchemaCprFactory(schema(), ConcurrencyModel.ACID, true, OperationType.WRITE);

        List<BookkeeperTransaction> firstHop = factory.createFrom(parent);
        assertFalse(firstHop.isEmpty(), "fixture must produce a first hop");

        BookkeeperTransaction cu = firstHop.get(0);
        assertTrue(cu.getOperations().stream().allMatch(op -> op.getType() == OperationType.WRITE),
                "C are all-WRITE by cuOperationType");
        assertFalse(factory.createFrom(cu).isEmpty(),
                "so a CU still seeds the next hop even with write-only sourcing on");
    }
}
