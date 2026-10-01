package test.java.scheduling;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import de.tum.tssimulator.core.TransactionFactory;
import de.tum.tssimulator.core.TransactionGroup;
import de.tum.tssimulator.core.TransactionIdGenerator;
import de.tum.tssimulator.state.TransactionGroupRegistry;

public class LifoSchedulerBundleTest {

    @Test
    public void lifoOrderIsReversed() {
        var idGen = new TransactionIdGenerator();
        var factory = new TransactionFactory(idGen);
        var registry = new TransactionGroupRegistry();

        var lifo = new LifoScheduler();

        TransactionGroup g1 = new TransactionGroup();
        registry.register(g1);
        var t1 = factory.createTransaction(TestSupport.testConfig(), g1);
        g1.setParent(t1);

        TransactionGroup g2 = new TransactionGroup();
        registry.register(g2);
        var t2 = factory.createTransaction(TestSupport.testConfig(), g2);
        g2.setParent(t2);

        TransactionGroup g3 = new TransactionGroup();
        registry.register(g3);
        var t3 = factory.createTransaction(TestSupport.testConfig(), g3);
        g3.setParent(t3);

        lifo.push(t1);
        lifo.push(t2);
        lifo.push(t3);

        assertTrue(lifo.hasReady());
        assertEquals(t3, lifo.nextTransaction());
        assertEquals(t2, lifo.nextTransaction());
        assertEquals(t1, lifo.nextTransaction());
        assertFalse(lifo.hasReady());
    }
}
