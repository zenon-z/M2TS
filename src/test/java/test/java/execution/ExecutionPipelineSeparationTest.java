package test.java.execution;

import de.tum.tssimulator.core.Transaction;
import de.tum.tssimulator.core.TransactionAggregateMetrics;
import de.tum.tssimulator.core.TransactionEvent;
import de.tum.tssimulator.execution.SimulatedProcessorUnit;
import de.tum.tssimulator.model.IOModel;
import de.tum.tssimulator.monitoring.SimulationLogger;
import de.tum.tssimulator.queue.QueuePort;
import de.tum.tssimulator.queue.QueueType;

import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class ExecutionPipelineSeparationTest {

    @BeforeEach
    public void setup() {
        Transaction.resetMetrics();
    }

    static class DummyQueue implements QueuePort {
        ArrayDeque<Transaction> io = new ArrayDeque<>();
        ArrayDeque<Transaction> cpu = new ArrayDeque<>();

        @Override
        public void enqueue(Transaction txn, QueueType type) {
            (type == QueueType.IO ? io : cpu).add(txn);
        }

        @Override
        public void enqueueAll(Iterable<Transaction> txns, QueueType type) {
            for (Transaction t : txns)
                enqueue(t, type);
        }

        @Override
        public Transaction dequeue(QueueType type) {
            return (type == QueueType.IO ? io : cpu).poll();
        }

        @Override
        public void prepend(Transaction txn, QueueType type) {
            (type == QueueType.IO ? io : cpu).addFirst(txn);
        }

        @Override
        public boolean contains(Transaction txn) {
            return io.contains(txn) || cpu.contains(txn);
        }

        @Override
        public boolean isEmpty(QueueType type) {
            return (type == QueueType.IO ? io : cpu).isEmpty();
        }

        @Override
        public int size(QueueType type) {
            return (type == QueueType.IO ? io : cpu).size();
        }

        @Override
        public int totalActiveTransactions() {
            return io.size() + cpu.size();
        }
    }

    static class DummyIO extends IOModel {
        public DummyIO() { super(test.java.util.TestConfigFactory.create(0,0,0,0,0,0,0, List.of(),0,0,0,0,0,0,0)); }
        @Override public long getIOTime(Transaction t) { return 5; }
        @Override public long getCPUTime(Transaction t) { return 5; }
        @Override public long getTotalTime(Transaction t) { return 10; }
        @Override public long getLockAcquisitionTime(Transaction t) { return 0; }
    }

    @BeforeEach
    public void setUp() {
        // Reset static transaction counters before each test to avoid test pollution
        TransactionAggregateMetrics.reset();
    }

    @Test
    public void simulatedProcessorUnitDoesNotCallOnComplete() {
        DummyQueue queue = new DummyQueue();
        SimulationLogger logger = new SimulationLogger("target/test-execution-pipeline.log");
        AtomicBoolean completedCalled = new AtomicBoolean(false);
        SimulatedProcessorUnit unit = new SimulatedProcessorUnit(QueueType.CPU, t -> completedCalled.set(true), logger);

        Transaction txn = new Transaction("T1", List.of(), null);
        txn.initializeState();
        // Transition to RUNNING state (transactions start in PENDING)
        txn.transitionTo(TransactionEvent.LOCKS_ACQUIRED);
        // push directly to unit
        unit.assign(txn, new DummyIO());
        // allow a couple of steps to complete CPU phase via tick
        long budgetPerTick = 10;
        for (int i=0;i<3 && !completedCalled.get();i++) {
            unit.step(budgetPerTick, queue, new DummyIO(), i * budgetPerTick);  // Pass virtualTime
        }

        // CPU completes; callback should be invoked, but Transaction.onComplete should
        // not be forced here
        Assertions.assertTrue(completedCalled.get(), "callback should be called when unit finishes CPU phase");
        // Transaction metrics are updated by tick-like accounting; onComplete
        // increments static counters, which are only updated when lifecycle completes
        // transaction
        // Ensure we didn't increment completedTransactions here
        Assertions.assertEquals(0, Transaction.getCompletedTransactions(),
                "onComplete should not be called inside SimulatedProcessorUnit");
    }
}
