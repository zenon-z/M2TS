package test.java.monitoring;

import de.tum.tssimulator.core.ExecutionPhase;
import de.tum.tssimulator.core.Transaction;
import de.tum.tssimulator.core.TransactionEvent;
import de.tum.tssimulator.core.TransactionGroup;
import de.tum.tssimulator.execution.CompositePhaseDispatcher;
import de.tum.tssimulator.execution.PhaseDispatcherGroup;
import de.tum.tssimulator.execution.SimulatedProcessorUnit;
import de.tum.tssimulator.lifecycle.TransactionLifecycleManager;
import de.tum.tssimulator.model.IOModel;
import de.tum.tssimulator.monitoring.SimulationLogger;
import de.tum.tssimulator.queue.CentralQueueManager;
import de.tum.tssimulator.queue.QueuePort;
import de.tum.tssimulator.queue.QueueType;
import test.java.util.TestIOModelFactory;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public class StructuredLoggingGrantPathTest {

    static class DummyIO extends IOModel {
        public DummyIO() { super(test.java.util.TestConfigFactory.create(0,0,0,0,0,0,0, List.of(),0,0,0,0,0,0,0)); }
        @Override public long getIOTime(Transaction t) { return 5; }
        @Override public long getCPUTime(Transaction t) { return 5; }
        @Override public long getTotalTime(Transaction t) { return 10; }
        @Override public long getLockAcquisitionTime(Transaction t) { return 0; }
    }

    @Test
    public void logsGrantPathPhasesAndCompletion() throws Exception {
        QueuePort queue = new CentralQueueManager(TestIOModelFactory.createTestIOModel());
        DummyIO io = new DummyIO();
        Path logPath = Path.of("target/grant-path.log");
        SimulationLogger logger = new SimulationLogger(logPath.toString());
        TransactionLifecycleManager lifecycle = new TransactionLifecycleManager(queue,
                test.java.util.TestConfigFactory.create(0,0,0,0,0,0,0, List.of(),0,0,0,0,0,0,0),
                logger, 100);

        AtomicBoolean completed = new AtomicBoolean(false);

        SimulatedProcessorUnit ioUnit = new SimulatedProcessorUnit(QueueType.IO, t -> {}, logger);
        SimulatedProcessorUnit cpuUnit = new SimulatedProcessorUnit(QueueType.CPU, t -> { lifecycle.complete(t); completed.set(true); }, logger);

        CompositePhaseDispatcher ioDisp = new CompositePhaseDispatcher(List.of(ioUnit), queue, io, QueueType.IO);
        CompositePhaseDispatcher cpuDisp = new CompositePhaseDispatcher(List.of(cpuUnit), queue, io, QueueType.CPU);
        PhaseDispatcherGroup group = new PhaseDispatcherGroup(List.of(ioDisp, cpuDisp));

        Transaction txn = new Transaction("T-LOG", List.of(), null);
        txn.initializeState();
        // Attach a group to align with production expectations
        TransactionGroup tg = new TransactionGroup();
        tg.setParent(txn);

        // Transition to RUNNING state (transactions start in PENDING)
        txn.transitionTo(TransactionEvent.LOCKS_ACQUIRED);
        
        // Initialize transaction to IO phase (skip LOCK phase for this test)
        txn.preparePhase(ExecutionPhase.IO, io);

        queue.enqueue(txn, QueueType.IO);

        long budgetPerTick = 10;
        int maxTicks = 10;
        int ticks = 0;
        while (!completed.get() && ticks < maxTicks) {
            group.dispatch(budgetPerTick, ticks * budgetPerTick);  // Pass virtualTime
            ticks++;
        }

        // Flush the logger by calling summary (which closes the writer)
        logger.summary();

        // Read log and assert presence of expected events
        String content = Files.readString(logPath);
        
        // Verify completion and unit phase logs
        Assertions.assertTrue(content.contains("\"TRANSACTION_COMPLETED\""), "should log transaction completed");
        Assertions.assertTrue(content.contains("phase=IO"), "should log IO phase in unit logs");
        Assertions.assertTrue(content.contains("phase=CPU"), "should log CPU phase in unit logs");
    }
}
