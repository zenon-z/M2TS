package test.java.core;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

import de.tum.tssimulator.core.ExecutionPhase;
import de.tum.tssimulator.core.Transaction;
import de.tum.tssimulator.core.TransactionEvent;
import de.tum.tssimulator.model.IOModel;

class TransactionTickTest {

    private static class FixedIOModel extends IOModel {
        public FixedIOModel() { super(test.java.util.TestConfigFactory.create(0,0,0,0,0,0,0, List.of(), 0,0,0,0,0,0,0)); }
        @Override public long getIOTime(Transaction t) { return 5; }
        @Override public long getCPUTime(Transaction t) { return 7; }
        @Override public long getLockAcquisitionTime(Transaction t) { return 0; }
        @Override public long getTotalTime(Transaction t) { return 12; }
    }

    @Test
    void advancesAcrossPhaseBoundaries() {
        Transaction txn = new Transaction("1", null, null);
        txn.initializeState();
        IOModel io = new FixedIOModel();

        // Transition to RUNNING state first (transactions start in PENDING)
        txn.transitionTo(TransactionEvent.LOCKS_ACQUIRED);
        
        // Initialize phase to IO with its duration (using preparePhase to skip LOCK)
        txn.preparePhase(ExecutionPhase.IO, io);
        assertSame(ExecutionPhase.IO, txn.getPhase());
        assertEquals(5, txn.getRemainingPhaseTime());

        // Use 3ms of IO
        long used = txn.tick(3, io);
        assertEquals(3, used);
        assertSame(ExecutionPhase.IO, txn.getPhase());
        assertEquals(2, txn.getRemainingPhaseTime());

        // Finish IO (2ms), transition to CPU and set CPU time to 7
        used = txn.tick(2, io);
        assertEquals(2, used);
        assertSame(ExecutionPhase.CPU, txn.getPhase());
        assertEquals(7, txn.getRemainingPhaseTime());

        // Finish CPU
        used = txn.tick(7, io);
        assertEquals(7, used);
        assertSame(ExecutionPhase.COMPLETE, txn.getPhase());
        assertEquals(0, txn.getRemainingPhaseTime());
    }
}
