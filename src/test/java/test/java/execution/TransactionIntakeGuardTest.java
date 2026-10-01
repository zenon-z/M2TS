package test.java.execution;

import java.util.List;

import de.tum.tssimulator.core.Transaction;
import de.tum.tssimulator.execution.TransactionIntakePhaseDispatcher;
import de.tum.tssimulator.execution.TransactionProcessor;
import de.tum.tssimulator.model.IOModel;
import de.tum.tssimulator.monitoring.SimulationLogger;
import de.tum.tssimulator.queue.CentralQueueManager;
import de.tum.tssimulator.queue.QueueType;
import de.tum.tssimulator.scheduling.FIFOScheduler;
import de.tum.tssimulator.scheduling.Scheduler;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import test.java.util.TestIOModelFactory;

public class TransactionIntakeGuardTest {

    private static class NoopIO extends IOModel {
        public NoopIO() { super(test.java.util.TestConfigFactory.create(0,0,0,0,0,0,0, List.of(), 0,0,0,0,0,0,0)); }
        @Override public long getIOTime(Transaction t) { return 0; }
        @Override public long getCPUTime(Transaction t) { return 0; }
        @Override public long getLockAcquisitionTime(Transaction t) { return 0; }
        @Override public long getTotalTime(Transaction t) { return 0; }
    }

    private static class NoopProcessor extends TransactionProcessor {
        public boolean called = false;
        public NoopProcessor() { super(null, null, null, null, null, null); }
        @Override public boolean process(Transaction txn) { called = true; return true; }
    }

    @Test
    public void intakeSkipsNullGroupTransactionAndLogsWarning() {
        CentralQueueManager qm = new CentralQueueManager(TestIOModelFactory.createTestIOModel());
        Scheduler scheduler = new FIFOScheduler(qm);
        NoopProcessor processor = new NoopProcessor();
        SimulationLogger logger = new SimulationLogger("target/test-intake-guard.log");
        // Pass queueManager to constructor so it's not null
        TransactionIntakePhaseDispatcher disp = new TransactionIntakePhaseDispatcher(
            scheduler, processor, new NoopIO(), logger, null, qm);

        // enqueue a transaction without a group
        Transaction orphan = new Transaction("ORPHAN", List.of(), null);
        orphan.initializeState();
        qm.enqueue(orphan, QueueType.PENDING);

        long used = disp.dispatch(10, 0);  // Pass virtualTime = 0
        Assertions.assertEquals(0, used, "intake should not consume budget");
        Assertions.assertFalse(processor.called, "processor should not be called for orphan txn");
    }
}
