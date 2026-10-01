package de.tum.tssimulator.bookkeeping;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

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

class SchemaCprFactoryTest {

    @Test
    void identityCorrespondenceKeepsTheSameInstanceId() {
        List<Model> models = createModels(2, 1);
        CprSchema schema = new CprSchemaBuilder(models, CprTopologyMode.UNIFORM, 1.0, 1.0, 1, 1L, null, false).build();
        SchemaCprFactory factory = new SchemaCprFactory(schema, ConcurrencyModel.ACID);

        Resource written = new Resource(42, models.get(0).properties().get(0));
        Transaction parent = createTransaction("T1",
                List.of(new Operation(OperationType.WRITE, written)), List.of(written));

        List<BookkeeperTransaction> bookkeepers = factory.createFrom(parent);

        assertEquals(schema.linksFrom(written.property()).size(), bookkeepers.size());
        assertTrue(!bookkeepers.isEmpty());
        for (BookkeeperTransaction bk : bookkeepers) {
            assertEquals(1, bk.getResources().size());
            assertEquals(42, bk.getResources().get(0).instanceId());
        }
    }

    @Test
    void readOnlyTransactionProducesNoBookkeepers() {
        List<Model> models = createModels(2, 1);
        CprSchema schema = new CprSchemaBuilder(models, CprTopologyMode.UNIFORM, 1.0, 1.0, 1, 1L, null, false).build();
        SchemaCprFactory factory = new SchemaCprFactory(schema, ConcurrencyModel.ACID);

        Resource read = new Resource(1, models.get(0).properties().get(0));
        Transaction parent = createTransaction("T1",
                List.of(new Operation(OperationType.READ, read)), List.of(read));

        assertEquals(0, factory.createFrom(parent).size());
    }

    // Mirrors ProgramShapeRegistry.Balance's shape: a single READ, no WRITE at all.
    @Test
    void balanceShapedTransactionProducesNoBookkeepers() {
        List<Model> models = createModels(2, 1);
        CprSchema schema = new CprSchemaBuilder(models, CprTopologyMode.UNIFORM, 1.0, 1.0, 1, 1L, null, false).build();
        SchemaCprFactory factory = new SchemaCprFactory(schema, ConcurrencyModel.ACID);

        Resource x = new Resource(7, models.get(0).properties().get(0));
        Transaction balance = createTransaction("Balance-1",
                List.of(new Operation(OperationType.READ, x)), List.of(x));

        assertEquals(0, factory.createFrom(balance).size());
    }

    // RANDOM_OPERATIONS-mode transactions carry no typed Operations at all (getOperations() is
    // empty) - there's no READ/WRITE distinction to filter on, so every resource must still be
    // treated as a source, or triggering silently goes to zero for that whole generation mode.
    @Test
    void operationLessTransactionTreatsEveryResourceAsASource() {
        List<Model> models = createModels(2, 1);
        CprSchema schema = new CprSchemaBuilder(models, CprTopologyMode.UNIFORM, 1.0, 1.0, 1, 1L, null, false).build();
        SchemaCprFactory factory = new SchemaCprFactory(schema, ConcurrencyModel.ACID);

        Resource resource = new Resource(9, models.get(0).properties().get(0));
        TransactionGroup group = new TransactionGroup();
        Transaction parent = new Transaction("T1", List.of(resource), group);
        parent.initializeState();
        group.setParent(parent);

        List<BookkeeperTransaction> bookkeepers = factory.createFrom(parent);

        assertEquals(schema.linksFrom(resource.property()).size(), bookkeepers.size());
        assertTrue(!bookkeepers.isEmpty());
    }

    @Test
    void cascadeSurvivesTheWriteFilterAcrossTwoHops() {
        // LINE over 3 single-property models: A.attr1 -> B.attr1 -> C.attr1. LINE's ceiling is
        // (n-1)/n = 2/3 (the tail model never emits), so semanticOverlap must stay at/under
        // that to avoid CprSchemaBuilder's fail-fast feasibility check.
        List<Model> models = createModels(3, 1);
        CprSchema schema = new CprSchemaBuilder(models, CprTopologyMode.LINE, 2.0 / 3.0, 1.0, 1, 1L, null, false).build();
        SchemaCprFactory factory = new SchemaCprFactory(schema, ConcurrencyModel.ACID);

        Resource a = new Resource(5, models.get(0).properties().get(0));
        Transaction parent = createTransaction("T1", List.of(new Operation(OperationType.WRITE, a)), List.of(a));

        List<BookkeeperTransaction> firstHop = factory.createFrom(parent);
        assertEquals(1, firstHop.size());
        BookkeeperTransaction bk = firstHop.get(0);
        assertEquals(models.get(1).properties().get(0), bk.getResources().get(0).property());
        assertTrue(bk.getOperations().stream().anyMatch(op -> op.getType() == OperationType.WRITE),
                "bookkeeper must carry a WRITE operation, or the cascade dies here");

        List<BookkeeperTransaction> secondHop = factory.createFrom(bk);
        assertEquals(1, secondHop.size());
        assertEquals(models.get(2).properties().get(0), secondHop.get(0).getResources().get(0).property());
        assertEquals(5, secondHop.get(0).getResources().get(0).instanceId());
    }

    private static List<Model> createModels(int modelCount, int propertiesPerModel) {
        return new ModelFactory(1000).createModels(Collections.nCopies(modelCount, propertiesPerModel));
    }

    private static Transaction createTransaction(String id, List<Operation> operations, List<Resource> resources) {
        TransactionGroup group = new TransactionGroup(null);
        Transaction transaction = new Transaction(id, 0L, resources, group, -1, operations, null);
        transaction.initializeState();
        group.setParent(transaction);
        return transaction;
    }
}
