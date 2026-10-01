package test.java.util;

import java.util.ArrayList;
import java.util.List;

import de.tum.tssimulator.bookkeeping.BookkeeperTransaction;
import de.tum.tssimulator.bookkeeping.BookkeepingReason;
import de.tum.tssimulator.core.ExecutionPhase;
import de.tum.tssimulator.core.Model;
import de.tum.tssimulator.core.ModelFactory;
import de.tum.tssimulator.core.Resource;
import de.tum.tssimulator.core.Transaction;
import de.tum.tssimulator.core.TransactionGroup;

/**
 * Factory for creating test objects with sensible defaults.
 * Provides a cleaner alternative to mocking for unit tests.
 */
public class TestObjectFactory {

    // Shared synthetic model pool backing the default/indexed resource helpers below - plenty
    // of properties/instances so tests picking a handful of low indices never collide.
    private static final Model DEFAULT_MODEL = new ModelFactory(1000).createModels(List.of(20)).get(0);

    /** The n-th (1-based) resource in the shared default model's instance-major ordering. */
    public static Resource resource(int n) {
        return DEFAULT_MODEL.getOwnedResources().get(n - 1);
    }

    private static List<Resource> resources(int... indices) {
        List<Resource> result = new ArrayList<>(indices.length);
        for (int i : indices) {
            result.add(resource(i));
        }
        return result;
    }

    /**
     * Create a transaction with minimal setup.
     */
    public static Transaction createTransaction(String id) {
        List<Model> models = new ArrayList<>();
        List<Resource> resources = resources(1, 2, 3);
        TransactionGroup group = new TransactionGroup(null);
        Transaction txn = new Transaction(id, resources, group);
        txn.initializeState();
        group.setParent(txn);
        return txn;
    }

    /**
     * Create a transaction with specific resources.
     */
    public static Transaction createTransaction(String id, List<Resource> resources) {
        List<Model> models = new ArrayList<>();
        TransactionGroup group = new TransactionGroup(null);
        Transaction txn = new Transaction(id, resources, group);
        txn.initializeState();
        group.setParent(txn);
        return txn;
    }

    /**
     * Create a transaction with models and resources.
     */
    public static Transaction createTransaction(String id, List<Model> models, List<Resource> resources) {
        TransactionGroup group = new TransactionGroup(null);
        Transaction txn = new Transaction(id, resources, group);
        txn.initializeState();
        group.setParent(txn);
        return txn;
    }

    /**
     * Create a transaction at a specific phase.
     */
    public static Transaction createTransactionAtPhase(String id, ExecutionPhase phase) {
        Transaction txn = createTransaction(id);
        advanceToPhase(txn, phase);
        return txn;
    }

    /**
     * Create a bookkeeper transaction.
     */
    public static BookkeeperTransaction createBookkeeper(Transaction parent, String id) {
        // Bookkeepers reference resources from parent - use realistic resource indices.
        return createBookkeeper(parent, id, resources(10, 11));
    }

    /**
     * Create a bookkeeper transaction with specific resources.
     */
    public static BookkeeperTransaction createBookkeeper(Transaction parent, String id, List<Resource> resources) {
        return BookkeeperTransaction.createAcid(parent, resources, BookkeepingReason.MODEL_INCONSISTENCY, null, 0);
    }

    /**
     * Create a bookkeeper at a specific phase.
     */
    public static BookkeeperTransaction createBookkeeperAtPhase(Transaction parent, String id, ExecutionPhase phase) {
        BookkeeperTransaction bk = createBookkeeper(parent, id);
        advanceToPhase(bk, phase);
        return bk;
    }

    /**
     * Create a model with default settings: a single-property model with the requested resource
     * count (name is derived by ModelFactory, not customizable - see ModelFactory).
     */
    public static Model createModel(String name, int numResources) {
        return new ModelFactory(numResources).createModels(List.of(1)).get(0);
    }

    /**
     * Create a transaction group with parent and bookkeepers.
     */
    public static TransactionGroup createGroup(Transaction parent, List<BookkeeperTransaction> bookkeepers) {
        TransactionGroup group = parent.getGroup();
        for (BookkeeperTransaction bk : bookkeepers) {
            group.addBookkeeper(bk);
        }
        return group;
    }

    /**
     * Advance transaction to the specified phase.
     */
    public static void advanceToPhase(Transaction txn, ExecutionPhase targetPhase) {
        while (txn.getPhase() != targetPhase) {
            if (txn.getPhase() == ExecutionPhase.COMPLETE) {
                // Already at terminal phase, can't advance further
                break;
            }
            txn.advancePhase();
        }
    }
}
