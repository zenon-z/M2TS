package test.java.scheduling;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedList;
import java.util.List;

import org.junit.jupiter.api.Test;

import de.tum.tssimulator.core.Transaction;
import de.tum.tssimulator.queue.QueuePort;
import de.tum.tssimulator.queue.QueueType;
import de.tum.tssimulator.scheduling.FIFOScheduler;

class SchedulerQueueContractTest {

    private static class FakeQueue implements QueuePort {
        LinkedList<Transaction> q = new LinkedList<>();
        @Override public void enqueue(Transaction txn, QueueType type) { if (type==QueueType.PENDING) q.add(txn); }
        @Override public void enqueueAll(Iterable<Transaction> txns, QueueType type) { for (Transaction t: txns) enqueue(t,type); }
        @Override public Transaction dequeue(QueueType type) { return type==QueueType.PENDING ? q.poll() : null; }
        @Override public void prepend(Transaction txn, QueueType type) { if (type==QueueType.PENDING) q.addFirst(txn); }
        @Override public boolean contains(Transaction txn) { return q.contains(txn); }
        @Override public boolean isEmpty(QueueType type) { return type==QueueType.PENDING ? q.isEmpty() : true; }
        @Override public int size(QueueType type) { return type==QueueType.PENDING ? q.size() : 0; }
        @Override public int totalActiveTransactions() { return q.size(); }
    }

    @Test
    void prependBehavior() {
        FakeQueue q = new FakeQueue();
        FIFOScheduler s = new FIFOScheduler(q);
        Transaction t1 = new Transaction("1", null, null);
        t1.initializeState();
        Transaction t2 = new Transaction("2", null, null);
        t2.initializeState();
        q.enqueue(t1, QueueType.PENDING);
        q.prepend(t2, QueueType.PENDING);
        assertTrue(s.hasReady());
        assertEquals("2", s.nextTransaction().getId());
        assertEquals("1", s.nextTransaction().getId());
        assertFalse(s.hasReady());
    }

    @Test
    void enqueueAllMaintainsFifoOrder() {
        FakeQueue q = new FakeQueue();
        FIFOScheduler s = new FIFOScheduler(q);
        Transaction t1 = new Transaction("1", null, null);
        t1.initializeState();
        Transaction t2 = new Transaction("2", null, null);
        t2.initializeState();
        Transaction t3 = new Transaction("3", null, null);
        t3.initializeState();
        q.enqueueAll(List.of(t1, t2, t3), QueueType.PENDING);
        assertEquals("1", s.nextTransaction().getId());
        assertEquals("2", s.nextTransaction().getId());
        assertEquals("3", s.nextTransaction().getId());
        assertFalse(s.hasReady());
    }

    @Test
    void nonPendingQueuesDoNotAffectFifo() {
        FakeQueue q = new FakeQueue();
        FIFOScheduler s = new FIFOScheduler(q);
        Transaction t1 = new Transaction("1", null, null);
        t1.initializeState();
        Transaction t2 = new Transaction("2", null, null);
        t2.initializeState();
        // Enqueue to CPU/IO queues should not impact scheduler's pending view
        q.enqueue(t1, QueueType.CPU);
        q.enqueue(t2, QueueType.IO);
        assertFalse(s.hasReady());
    }

    @Test
    void fifoBehaviorViaQueuePort() {
        FakeQueue q = new FakeQueue();
        FIFOScheduler s = new FIFOScheduler(q);
        Transaction t1 = new Transaction("1", null, null);
        t1.initializeState();
        Transaction t2 = new Transaction("2", null, null);
        t2.initializeState();
        q.enqueue(t1, QueueType.PENDING);
        q.enqueue(t2, QueueType.PENDING);

        assertTrue(s.hasReady());
        assertEquals("1", s.nextTransaction().getId());
        assertEquals("2", s.nextTransaction().getId());
        assertFalse(s.hasReady());
    }
}
