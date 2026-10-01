package de.tum.tssimulator.queue;

import java.util.List;

import de.tum.tssimulator.core.Transaction;

/**
 * Extended queue interface with additional operations beyond basic QueuePort.
 * Adds support for transaction removal, backoff enqueueing, and time tracking.
 */
public interface QueueManager extends QueuePort {
    void enqueue(Transaction t, QueueType type);
    void enqueueAll(List<Transaction> ts, QueueType type);
    Transaction dequeue(QueueType type);
    boolean isEmpty(QueueType type);
    int size(QueueType type);
    int totalActiveTransactions();
    void prepend(Transaction t, QueueType type);
    boolean contains(Transaction t);
    
    /**
     * Remove a transaction from all queues (if present).
     * Used during abort to ensure transaction is not in any queue.
     * 
     * @param txn The transaction to remove
     * @return true if transaction was removed from at least one queue, false if not found
     */
    boolean remove(Transaction txn);
    
    /**
     * Enqueue a transaction with exponential backoff after abort.
     * Implementations may choose to apply backoff delay or enqueue immediately.
     * 
     * @param txn The restarted transaction
     * @param currentTime Current simulation time
     * @param retryCount Number of times this transaction has been aborted and restarted
     */
    void enqueueWithBackoff(Transaction txn, long currentTime, int retryCount);
    
    /**
     * Get the current simulation time.
     * 
     * @return Current virtual time in the simulation
     */
    long getCurrentTime();
}
