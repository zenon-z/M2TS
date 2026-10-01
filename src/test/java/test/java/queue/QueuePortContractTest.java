package test.java.queue;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import de.tum.tssimulator.core.Transaction;
import de.tum.tssimulator.core.TransactionFactory;
import de.tum.tssimulator.core.TransactionGroup;
import de.tum.tssimulator.core.TransactionIdGenerator;
import de.tum.tssimulator.queue.CentralQueueManager;
import de.tum.tssimulator.queue.QueueManager;
import de.tum.tssimulator.queue.QueueType;
import de.tum.tssimulator.state.TransactionGroupRegistry;
import test.java.util.TestIOModelFactory;

public class QueuePortContractTest {
    private QueueManager queues;
    private TransactionFactory factory;
    private TransactionGroupRegistry registry;

    @BeforeEach
    public void setup() {
        queues = new CentralQueueManager(TestIOModelFactory.createTestIOModel());
        registry = new TransactionGroupRegistry();
        factory = new TransactionFactory(new TransactionIdGenerator());
    }

    private Transaction newTxn() {
        TransactionGroup g = new TransactionGroup();
        registry.register(g);
        Transaction t = factory.createTransaction(test.java.scheduling.TestSupport.testConfig(), g);
        g.setParent(t);
        return t;
    }

    @Test
    public void emptyDequeueReturnsNull() {
        for (QueueType type : QueueType.values()) {
            assertTrue(queues.isEmpty(type), "Queue should be empty for type " + type);
            assertEquals(0, queues.size(type));
            assertNull(queues.dequeue(type), "Dequeue on empty should return null for type " + type);
        }
    }

    @Test
    public void repeatedPrependMovesToFront() {
        Transaction a = newTxn();
        Transaction b = newTxn();
        Transaction c = newTxn();

        queues.enqueue(a, QueueType.PENDING);
        queues.enqueue(b, QueueType.PENDING);
        queues.enqueue(c, QueueType.PENDING);
        assertEquals(3, queues.size(QueueType.PENDING));

        // Prepend B twice - should be at the very front
        queues.prepend(b, QueueType.PENDING);
        queues.prepend(b, QueueType.PENDING);

        assertEquals(5, queues.size(QueueType.PENDING)); // two prepends add elements at front

        Transaction first = queues.dequeue(QueueType.PENDING);
        Transaction second = queues.dequeue(QueueType.PENDING);
        Transaction third = queues.dequeue(QueueType.PENDING);

        assertTrue(first == b || second == b);

        // Remove remaining items and assert sizes update
        queues.dequeue(QueueType.PENDING);
        assertEquals(1, queues.size(QueueType.PENDING));
        queues.dequeue(QueueType.PENDING);
        assertEquals(0, queues.size(QueueType.PENDING));
        assertTrue(queues.isEmpty(QueueType.PENDING));
    }

    @Test
    public void sizeConsistency() {
        Transaction p1 = newTxn();
        Transaction p2 = newTxn();
        Transaction b1 = newTxn();

        assertEquals(0, queues.totalActiveTransactions());

        queues.enqueue(p1, QueueType.PENDING);
        queues.enqueue(p2, QueueType.PENDING);
        assertEquals(2, queues.size(QueueType.PENDING));
        assertFalse(queues.isEmpty(QueueType.PENDING));

        queues.enqueue(b1, QueueType.BLOCKED_TXN);
        assertEquals(1, queues.size(QueueType.BLOCKED_TXN));

        assertEquals(3, queues.totalActiveTransactions());

        // Move one to front and then dequeue
        queues.prepend(p2, QueueType.PENDING);
        assertEquals(3, queues.size(QueueType.PENDING));
        Transaction got1 = queues.dequeue(QueueType.PENDING);
        assertNotNull(got1);
        assertEquals(2, queues.size(QueueType.PENDING));

        // Dequeue rest
        queues.dequeue(QueueType.PENDING);
        queues.dequeue(QueueType.PENDING);
        assertTrue(queues.isEmpty(QueueType.PENDING));
        assertEquals(0, queues.size(QueueType.PENDING));

        // Blocked still has one
        assertFalse(queues.isEmpty(QueueType.BLOCKED_TXN));
        assertEquals(1, queues.size(QueueType.BLOCKED_TXN));

        // Now drain blocked
        queues.dequeue(QueueType.BLOCKED_TXN);
        assertEquals(0, queues.totalActiveTransactions());
        assertTrue(queues.isEmpty(QueueType.BLOCKED_TXN));
    }
}
