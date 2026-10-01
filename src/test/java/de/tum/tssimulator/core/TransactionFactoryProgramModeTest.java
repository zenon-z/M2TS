package de.tum.tssimulator.core;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import de.tum.tssimulator.config.SimulatorConfig;
import de.tum.tssimulator.config.TransactionGenerationMode;
import de.tum.tssimulator.core.program.Operation;
import de.tum.tssimulator.core.program.OperationType;
import de.tum.tssimulator.core.program.ProgramShape;
import de.tum.tssimulator.core.program.ProgramShapeRegistry;
import de.tum.tssimulator.core.program.TransactionProgram;
import de.tum.tssimulator.core.program.TransactionProgramFactory;
import de.tum.tssimulator.locking.core.LockType;
import de.tum.tssimulator.locking.core.OperationLockModes;

class TransactionFactoryProgramModeTest {

    private static List<Model> models(List<Integer> propertiesPerModel, int instancePoolSize) {
        return new ModelFactory(instancePoolSize).createModels(propertiesPerModel);
    }

    private static List<TransactionProgram> defaultPrograms(List<Model> modelPool) {
        List<ProgramShape> shapes = ProgramShapeRegistry.defaultShapes();
        return TransactionProgramFactory.createPrograms(shapes, modelPool);
    }

    private static SimulatorConfig.Builder baseBuilder(List<Model> modelPool,
            List<TransactionProgram> programPool) {
        return new SimulatorConfig.Builder()
                .totalModels(modelPool.size())
                .ioTimePerResource(1)
                .cpuTimePerResource(1)
                .initialTransactionCount(1)
                .totalTransactionSteps(1)
                .simulationDurationSeconds(1)
                .modelPool(modelPool)
                .maxTransactionModels(3)
                .maxTransactionResources(5)
                .lockIOTime(1)
                .lockCPUTimePerResource(1)
                .numIoProcessors(1)
                .numCpuProcessors(1)
                .totalLocks(10)
                .transactionGenerationMode(TransactionGenerationMode.TRANSACTION_PROGRAMS)
                .programPool(programPool);
    }

    private static TransactionProgram findProgram(List<TransactionProgram> pool, String shapeName) {
        return pool.stream().filter(p -> p.id().startsWith(shapeName + "@")).findFirst().orElseThrow();
    }

    @Test
    void transferOperationsResolveSameVariableToSameResourceDifferentToDifferent() {
        List<Model> modelPool = models(List.of(5, 5, 5), 90);
        List<TransactionProgram> programPool = defaultPrograms(modelPool);
        SimulatorConfig config = baseBuilder(modelPool, programPool).build();

        TransactionFactory factory = new TransactionFactory(new TransactionIdGenerator(), 7L);

        for (int i = 0; i < 200; i++) {
            Transaction txn = factory.createTransaction(config, new TransactionGroup());
            if (txn.getProgramName() == null || !txn.getProgramName().startsWith("Transfer@")) {
                continue;
            }
            List<Operation> ops = txn.getOperations();
            assertEquals(4, ops.size());
            // Steps 0,1 are on x; steps 2,3 are on y - Transfer's x and y share one property.
            assertEquals(ops.get(0).getResource(), ops.get(1).getResource());
            assertEquals(ops.get(2).getResource(), ops.get(3).getResource());
            assertNotEquals(ops.get(0).getResource(), ops.get(2).getResource());

            // Distinct resources invariant, one per declared variable.
            assertEquals(2, txn.getResources().size());
            assertEquals(new HashSet<>(txn.getResources()).size(), txn.getResources().size());
            return; // Found and verified a Transfer instance
        }
        throw new AssertionError("No Transfer transaction generated across 200 draws - check WeightedProgramSelector");
    }

    @Test
    void resourcesAreDistinctWhileOperationsPreserveRepeats() {
        List<Model> modelPool = models(List.of(5, 5), 90);
        List<TransactionProgram> programPool = defaultPrograms(modelPool);
        TransactionProgram deposit = findProgram(programPool, "Deposit");
        SimulatorConfig config = baseBuilder(modelPool, List.of(deposit)).build();

        TransactionFactory factory = new TransactionFactory(new TransactionIdGenerator(), 11L);
        Transaction txn = factory.createTransaction(config, new TransactionGroup());

        assertEquals(deposit.id(), txn.getProgramName());
        assertEquals(deposit.variables().size(), txn.getResources().size());
        assertEquals(deposit.steps().size(), txn.getOperations().size());
        // Deposit is [(READ,x),(WRITE,x)] - both operations share the one resource.
        assertEquals(txn.getOperations().get(0).getResource(), txn.getOperations().get(1).getResource());
    }

    @Test
    void sharedPropertyVariablesAlwaysDrawDistinctInstancesAcrossManySeeds() {
        // Transfer's x/y share one property - exercise the distinct-instance draw across many
        // seeds to make sure it never collapses the two variables onto the same instance.
        List<Model> modelPool = models(List.of(5, 5, 5), 300);
        List<TransactionProgram> programPool = defaultPrograms(modelPool);
        TransactionProgram transfer = findProgram(programPool, "Transfer");
        SimulatorConfig config = baseBuilder(modelPool, List.of(transfer)).build();

        for (long seed = 0; seed < 200; seed++) {
            TransactionFactory factory = new TransactionFactory(new TransactionIdGenerator(), seed);
            Transaction txn = factory.createTransaction(config, new TransactionGroup());
            List<Resource> resources = txn.getResources();
            Set<Resource> distinct = new HashSet<>(resources);
            assertEquals(resources.size(), distinct.size(),
                    "Duplicate resource across distinct variables at seed " + seed);
        }
    }

    @Test
    void affectedModelsIsExactlyTheProgramsModel() {
        List<Model> modelPool = models(List.of(5), 90);
        List<TransactionProgram> programPool = defaultPrograms(modelPool);
        TransactionProgram transfer = findProgram(programPool, "Transfer");
        // Single-model pool by design (the test asserts affectedModels is exactly one model) -
        // semanticOverlap>0 needs >=2 models, unrelated to what this test exercises.
        SimulatorConfig config = baseBuilder(modelPool, List.of(transfer)).semanticOverlap(0.0).build();

        TransactionFactory factory = new TransactionFactory(new TransactionIdGenerator(), 3L);
        assertDoesNotThrow(() -> {
            for (int i = 0; i < 50; i++) {
                Transaction txn = factory.createTransaction(config, new TransactionGroup());
                assertEquals(List.of(transfer.model()), txn.getAffectedModels());
            }
        });
    }

    @Test
    void readOnlyProgramProducesSharedLockAndReadOnlyId() {
        List<Model> modelPool = models(List.of(5, 5), 90);
        List<TransactionProgram> programPool = defaultPrograms(modelPool);
        TransactionProgram balance = findProgram(programPool, "Balance");
        SimulatorConfig config = baseBuilder(modelPool, List.of(balance)).build();

        TransactionFactory factory = new TransactionFactory(new TransactionIdGenerator(), 5L);
        Transaction txn = factory.createTransaction(config, new TransactionGroup());

        assertTrue(txn.getOperations().stream().noneMatch(op -> op.getType() == OperationType.WRITE));
        // Was assertEquals(SHARED, txn.getLockType()). getLockType() is gone: a transaction no
        // longer has ONE mode, so the assertion is now per lock id. Balance reads a single variable,
        // so every folded mode is SHARED.
        assertTrue(OperationLockModes.foldByLockId(txn, null).values().stream()
                        .allMatch(mode -> mode == LockType.SHARED),
                "a read-only program must request only SHARED locks");
        // Was assertTrue(txn.getId().endsWith("-RO")). The ID no longer encodes read-only-ness -
        // it was a derived property living inside an identifier, and under RANDOM_OPERATIONS it was
        // set from txCounter parity rather than from the operations. Transaction.isReadOnly() reads
        // the operations, which is the only thing that can answer the question.
        assertTrue(txn.isReadOnly(), "a program with no WRITE step is read-only");
    }

    @Test
    void writeProgramProducesExclusiveLockAndReadWriteId() {
        List<Model> modelPool = models(List.of(5, 5), 90);
        List<TransactionProgram> programPool = defaultPrograms(modelPool);
        TransactionProgram deposit = findProgram(programPool, "Deposit");
        SimulatorConfig config = baseBuilder(modelPool, List.of(deposit)).build();

        TransactionFactory factory = new TransactionFactory(new TransactionIdGenerator(), 5L);
        Transaction txn = factory.createTransaction(config, new TransactionGroup());

        assertTrue(txn.getOperations().stream().anyMatch(op -> op.getType() == OperationType.WRITE));
        // Deposit is R x then W x - one resource touched twice, so exactly one lock id, folded to
        // the stronger mode. Under the old whole-transaction getLockType() this was EXCLUSIVE
        // because ANY write made the WHOLE transaction exclusive; now it is EXCLUSIVE because that
        // particular lock is written. Same answer here, different and narrower reason.
        var folded = OperationLockModes.foldByLockId(txn, null);
        assertEquals(1, folded.size(), "R x then W x is one lock, not two");
        assertEquals(LockType.EXCLUSIVE, folded.values().iterator().next());
        assertTrue(!txn.getId().endsWith("-RO"));
    }

    @Test
    void restartTransactionPreservesOperationsAndProgramNameWithoutRecomputation() {
        List<Model> modelPool = models(List.of(5, 5), 90);
        List<TransactionProgram> programPool = defaultPrograms(modelPool);
        TransactionProgram transfer = findProgram(programPool, "Transfer");
        SimulatorConfig config = baseBuilder(modelPool, List.of(transfer)).build();

        TransactionFactory factory = new TransactionFactory(new TransactionIdGenerator(), 9L);
        Transaction original = factory.createTransaction(config, new TransactionGroup());
        assertNotNull(original.getProgramName());

        Transaction restarted = factory.restartTransaction(original);

        assertEquals(original.getProgramName(), restarted.getProgramName());
        assertEquals(original.getOperations(), restarted.getOperations());
        assertEquals(original.getResources(), restarted.getResources());
        assertNotEquals(original.getId(), restarted.getId());
    }
}
