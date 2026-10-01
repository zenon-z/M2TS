package de.tum.tssimulator.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

import de.tum.tssimulator.bookkeeping.BookkeeperTransaction;
import de.tum.tssimulator.bookkeeping.BookkeepingReason;
import de.tum.tssimulator.config.SimulatorConfig;
import de.tum.tssimulator.core.Model;
import de.tum.tssimulator.core.ModelFactory;
import de.tum.tssimulator.core.Resource;
import de.tum.tssimulator.core.Transaction;
import de.tum.tssimulator.core.TransactionGroup;

/**
 * Regression test pinning 1:1 IO/CPU scaling for BookkeeperTransaction vs. a plain Transaction
 * with equal resource count - the bookkeeper 10x-speedup special case was removed
 * unconditionally from IOModel.
 */
class IOModelTest {
    private static final int IO_TIME_PER_RESOURCE = 200;
    private static final int CPU_TIME_PER_RESOURCE = 100;

    private static SimulatorConfig config(List<Model> modelPool) {
        return new SimulatorConfig(
                modelPool.size(), 0, IO_TIME_PER_RESOURCE, CPU_TIME_PER_RESOURCE,
                1, 1, 1, modelPool, modelPool.size(), 5, 1, 1, 1, 1, 10);
    }

    @Test
    void bookkeeperAndPlainTransactionScaleIdenticallyForIoTime() {
        Model model = new ModelFactory(5).createModels(List.of(3)).get(0);
        List<Resource> resources = model.getOwnedResources().subList(0, 2);

        TransactionGroup group = new TransactionGroup();
        Transaction parent = new Transaction("T1", resources, group);
        group.setParent(parent);
        BookkeeperTransaction bookkeeper = BookkeeperTransaction.createAcid(
                parent, resources, BookkeepingReason.MODEL_INCONSISTENCY, model, 1);

        IOModel ioModel = new IOModel(config(List.of(model)));

        long expectedIoTime = resources.size() * (long) IO_TIME_PER_RESOURCE;
        assertEquals(expectedIoTime, ioModel.getIOTime(parent));
        assertEquals(expectedIoTime, ioModel.getIOTime(bookkeeper),
                "bookkeepers must scale 1:1 with plain transactions - no 10x speedup");
    }

    @Test
    void bookkeeperAndPlainTransactionScaleIdenticallyForCpuTime() {
        Model model = new ModelFactory(5).createModels(List.of(3)).get(0);
        List<Resource> resources = model.getOwnedResources().subList(0, 2);

        TransactionGroup group = new TransactionGroup();
        Transaction parent = new Transaction("T1", resources, group);
        group.setParent(parent);
        BookkeeperTransaction bookkeeper = BookkeeperTransaction.createAcid(
                parent, resources, BookkeepingReason.MODEL_INCONSISTENCY, model, 1);

        IOModel ioModel = new IOModel(config(List.of(model)));

        long expectedCpuTime = resources.size() * (long) CPU_TIME_PER_RESOURCE;
        assertEquals(expectedCpuTime, ioModel.getCPUTime(parent));
        assertEquals(expectedCpuTime, ioModel.getCPUTime(bookkeeper),
                "bookkeepers must scale 1:1 with plain transactions - no 10x speedup");
    }
}
