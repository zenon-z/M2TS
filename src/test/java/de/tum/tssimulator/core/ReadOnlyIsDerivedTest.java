package de.tum.tssimulator.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import de.tum.tssimulator.config.SimulatorConfig;
import de.tum.tssimulator.config.SimulatorParameters;
import de.tum.tssimulator.config.TransactionGenerationMode;
import de.tum.tssimulator.core.program.Operation;
import de.tum.tssimulator.core.program.OperationType;

/**
 * Read-only-ness is derived from the operations, and lives nowhere else.
 *
 * <p>It used to be encoded in the transaction ID as a "-RO" suffix - a derived property inside an
 * identifier, which is a second source of truth by construction. Under {@code RANDOM_OPERATIONS} the
 * flag was {@code txCounter % 2 == 0}: alternating parity, computed BEFORE the operations were
 * synthesised and never reconciled with them. At {@code writeFraction = 1.0} that labelled half of
 * an all-WRITE workload read-only.
 *
 * <p>That substring was what the deleted {@code getLockType()} matched on to decide a whole
 * transaction's lock mode. Step 2 removed the reader; the writer outlived it, which is the usual
 * shape - a mechanism looks retired because its consumer is gone, while it keeps producing.
 */
class ReadOnlyIsDerivedTest {

    private static final List<Model> POOL = new ModelFactory(200).createModels(List.of(1));

    private static Resource res(int instance) {
        return new Resource(instance, POOL.get(0).properties().get(0));
    }

    private static Transaction txn(String id, List<Operation> ops) {
        List<Resource> resources = new ArrayList<>(
                ops.stream().map(Operation::getResource).distinct().toList());
        return new Transaction(id, 0L, resources, new TransactionGroup(), -1, ops, null);
    }

    @Test
    void aTransactionWithNoWriteIsReadOnly() {
        assertTrue(txn("T1", List.of(
                new Operation(OperationType.READ, res(1)),
                new Operation(OperationType.READ, res(2)))).isReadOnly());
    }

    @Test
    void anySingleWriteMakesItNotReadOnly() {
        assertFalse(txn("T1", List.of(
                new Operation(OperationType.READ, res(1)),
                new Operation(OperationType.WRITE, res(2)))).isReadOnly());
    }

    /**
     * Read-only means "provably performs no write". With no operations there is nothing to prove it
     * from, so the answer is false - vacuous truth would be the optimistic direction, and being
     * wrong optimistically about write-ness is the worse way round.
     */
    @Test
    void anOperationLessTransactionIsNotClaimedReadOnly() {
        Transaction t = new Transaction("T1", List.of(res(1)), new TransactionGroup());
        assertFalse(t.isReadOnly());
    }

    /** The ID carries identity only - no suffix, and nothing to parse a property out of. */
    @Test
    void idsCarryNoReadOnlyMarker() {
        TransactionIdGenerator idGen = new TransactionIdGenerator();
        for (int i = 0; i < 10; i++) {
            String id = idGen.nextId();
            assertFalse(id.contains("RO"),
                    "an ID must not encode a derived property: " + id);
            assertEquals("T" + i, id);
        }
    }

    /** Uniqueness was always the counter, never the suffix - so dropping it changes nothing here. */
    @Test
    void idsRemainUniqueWithoutTheSuffix() {
        TransactionIdGenerator idGen = new TransactionIdGenerator();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            assertTrue(seen.add(idGen.nextId()), "duplicate id at " + i);
        }
        assertEquals(1000, seen.size());
    }

    /**
     * The defect itself, at the level it occurred: synthesised transactions must agree with their
     * own operations. At {@code writeFraction = 1.0} every operation is a WRITE, so NOTHING may
     * report read-only - the old parity rule made half of them do exactly that.
     */
    @Test
    void synthesisedTransactionsAgreeWithTheirOperations() {
        SimulatorConfig config = SimulatorConfig.fromParameters(SimulatorParameters.builder()
                .transactionGenerationMode(TransactionGenerationMode.RANDOM_OPERATIONS)
                .useRandomResourceSelection(true)
                .totalModels(1).propertiesPerModel(List.of(1))
                .instancePoolSize(200).totalLocks(200).maxTransactionModels(1)
                .useConstantTransactionSize(true).constantTransactionSize(5)
                .semanticOverlap(0.0)
                .writeFraction(1.0)
                .build(), POOL);

        TransactionFactory factory = new TransactionFactory(new TransactionIdGenerator(), 1000003L);
        int readOnlyCount = 0;
        for (int i = 0; i < 40; i++) {
            Transaction t = factory.createTransaction(config, new TransactionGroup());
            assertFalse(t.getId().contains("RO"), "id must not carry the marker: " + t.getId());
            boolean noWrites = t.getOperations().stream()
                    .noneMatch(op -> op.getType() == OperationType.WRITE);
            assertEquals(noWrites, t.isReadOnly(),
                    t.getId() + ": isReadOnly() must match the operations it actually holds");
            if (t.isReadOnly()) {
                readOnlyCount++;
            }
        }
        assertEquals(0, readOnlyCount,
                "at writeFraction=1.0 every operation is a WRITE, so no transaction is read-only - "
                        + "the parity rule reported half of them as read-only here");
    }

    /** At a mixed write fraction the derived flag tracks the operations rather than a counter. */
    @Test
    void atAMixedWriteFractionTheFlagFollowsTheOperations() {
        SimulatorConfig config = SimulatorConfig.fromParameters(SimulatorParameters.builder()
                .transactionGenerationMode(TransactionGenerationMode.RANDOM_OPERATIONS)
                .useRandomResourceSelection(true)
                .totalModels(1).propertiesPerModel(List.of(1))
                .instancePoolSize(200).totalLocks(200).maxTransactionModels(1)
                .useConstantTransactionSize(true).constantTransactionSize(1)
                .semanticOverlap(0.0)
                .writeFraction(0.5)
                .build(), POOL);

        TransactionFactory factory = new TransactionFactory(new TransactionIdGenerator(), 1000003L);
        int readOnly = 0;
        for (int i = 0; i < 60; i++) {
            Transaction t = factory.createTransaction(config, new TransactionGroup());
            boolean noWrites = t.getOperations().stream()
                    .noneMatch(op -> op.getType() == OperationType.WRITE);
            assertEquals(noWrites, t.isReadOnly(), t.getId());
            if (t.isReadOnly()) {
                readOnly++;
            }
        }
        // Single-operation transactions at writeFraction=0.5: both kinds must actually occur, or
        // this fixture proves nothing about the flag tracking anything.
        assertTrue(readOnly > 0 && readOnly < 60,
                "fixture must produce both read-only and writing transactions, got " + readOnly + "/60");
    }
}
