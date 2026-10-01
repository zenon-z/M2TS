package de.tum.tssimulator.execution;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import de.tum.tssimulator.core.Transaction;
import de.tum.tssimulator.lifecycle.TransactionLifecycleManager;
import de.tum.tssimulator.lifecycle.TransactionPoolManager;

public class StepStrategyTest {

    private static class DummyDispatcher implements PhaseDispatcher {
        @Override public long dispatch(long budget, long virtualTime) { return 0L; }
    }
    // TransactionPoolManager is a concrete class; provide a no-op subclass
    private static class DummyPool extends TransactionPoolManager {
        DummyPool() { super(null, null, 0); }
        @Override public void maintain() { }
    }
    // TransactionLifecycleManager is a concrete class; provide a no-op subclass
    private static class DummyLifecycle extends TransactionLifecycleManager {
        DummyLifecycle() { super(null, null, null, 100); }
        @Override public void retrySpecificTransactions(Set<Transaction> txns) { }
    }

    @Test
    void fixedTimeProducesOutcomes() {
        StepContext dummy = new StepContext(new DummyDispatcher(), new DummyPool(), new DummyLifecycle(), null, null);
        FixedTimeStepStrategy strat = new FixedTimeStepStrategy(1000, 2000, dummy);
        StepOutcome o1 = strat.step();
        assertEquals(1000L, o1.getNewVirtualTime());
        assertFalse(o1.isFinished());
        StepOutcome o2 = strat.step();
        assertEquals(2000L, o2.getNewVirtualTime());
        assertTrue(o2.isFinished());
    }
}
