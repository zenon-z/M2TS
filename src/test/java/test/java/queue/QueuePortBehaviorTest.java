package test.java.queue;

import java.util.List;

import de.tum.tssimulator.core.Transaction;
import de.tum.tssimulator.queue.CentralQueueManager;
import de.tum.tssimulator.queue.QueuePort;
import de.tum.tssimulator.queue.QueueType;
import test.java.util.TestIOModelFactory;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class QueuePortBehaviorTest {

    @Test
    public void prependMultipleTimesIsLifo() {
        QueuePort q = new CentralQueueManager(TestIOModelFactory.createTestIOModel());
        Transaction a = new Transaction("A", List.of(), null);
        a.initializeState();
        Transaction b = new Transaction("B", List.of(), null);
        b.initializeState();
        q.enqueue(a, QueueType.IO);
        q.prepend(b, QueueType.IO);
        q.prepend(a, QueueType.IO); // move A to head

        Assertions.assertEquals("A", q.dequeue(QueueType.IO).getId());
        Assertions.assertEquals("B", q.dequeue(QueueType.IO).getId());
    }

    @Test
    public void enqueueAllFifoAndPrependTakesPrecedence() {
        QueuePort q = new CentralQueueManager(TestIOModelFactory.createTestIOModel());
        Transaction a = new Transaction("A", List.of(), null);
        a.initializeState();
        Transaction b = new Transaction("B", List.of(), null);
        b.initializeState();
        Transaction c = new Transaction("C", List.of(), null);
        c.initializeState();

        q.enqueueAll(List.of(a, b), QueueType.CPU);
        q.prepend(c, QueueType.CPU);

        Assertions.assertEquals("C", q.dequeue(QueueType.CPU).getId(), "prepended goes first");
        Assertions.assertEquals("A", q.dequeue(QueueType.CPU).getId(), "FIFO among enqueued items");
        Assertions.assertEquals("B", q.dequeue(QueueType.CPU).getId());
    }
}
