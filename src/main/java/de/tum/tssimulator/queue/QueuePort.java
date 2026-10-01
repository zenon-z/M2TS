package de.tum.tssimulator.queue;

import de.tum.tssimulator.core.Transaction;

public interface QueuePort {
    void enqueue(Transaction txn, QueueType type);
    void enqueueAll(Iterable<Transaction> txns, QueueType type);
    Transaction dequeue(QueueType type);
    void prepend(Transaction txn, QueueType type);
    boolean contains(Transaction txn);
    boolean isEmpty(QueueType type);
    int size(QueueType type);
    int totalActiveTransactions();
}
