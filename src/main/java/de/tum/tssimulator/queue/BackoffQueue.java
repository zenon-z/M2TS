package de.tum.tssimulator.queue;

import de.tum.tssimulator.core.Transaction;
import java.util.Comparator;
import java.util.HashSet;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Priority queue for transactions in exponential backoff.
 * Orders transactions by earliestRetryTime (min-heap).
 * Only the transaction with the earliest retry time needs to be checked.
 */
class BackoffQueue {
    private final PriorityQueue<Transaction> heap;
    private final Set<Transaction> txnSet; // For O(1) contains() lookup

    public BackoffQueue() {
        this(1.0);
    }

    public BackoffQueue(double backoffScale) {
        if (!(backoffScale > 0.0) || Double.isNaN(backoffScale)) {
            throw new IllegalArgumentException("backoffScale must be > 0, got " + backoffScale);
        }
        this.backoffScale = backoffScale;
        // Min-heap ordered by earliestRetryTime
        this.heap = new PriorityQueue<>(Comparator.comparingLong(Transaction::getEarliestRetryTime));
        this.txnSet = new HashSet<>();
    }

    /**
     * Add transaction to backoff queue with absolute retry time.
     * Transaction must have earliestRetryTime set to an absolute time.
     */
    public void enqueue(Transaction txn) {
        heap.offer(txn);
        txnSet.add(txn);
    }

    /**
     * Add transaction to backoff queue with relative delay.
     * Converts relative delay to absolute time using currentTime.
     */
    public void enqueue(Transaction txn, long currentTime, long relativeDelay) {
        txn.setEarliestRetryTime(currentTime + relativeDelay);
        heap.offer(txn);
        txnSet.add(txn);
    }

    /**
     * Check if any transactions are ready to retry at the given time.
     * Since heap is ordered by earliestRetryTime, we only need to check the
     * minimum.
     */
    public boolean hasReadyTransactions(long currentTime) {
        Transaction earliest = heap.peek();
        return earliest != null && earliest.getEarliestRetryTime() <= currentTime;
    }

    /**
     * Dequeue all transactions that are ready to retry at the given time.
     * Returns null if no transactions are ready.
     */
    public Transaction dequeueReady(long currentTime) {
        Transaction earliest = heap.peek();
        if (earliest != null && earliest.getEarliestRetryTime() <= currentTime) {
            Transaction txn = heap.poll();
            txnSet.remove(txn);
            return txn;
        }
        return null;
    }

    public boolean isEmpty() {
        return heap.isEmpty();
    }

    public int size() {
        return heap.size();
    }

    /**
     * Remove a specific transaction from the backoff queue.
     * Used when a transaction needs to be aborted or cancelled.
     */
    public boolean remove(Transaction txn) {
        boolean removed = heap.remove(txn);
        if (removed) {
            txnSet.remove(txn);
        }
        return removed;
    }

    /**
     * Check if a specific transaction is in the backoff queue.
     * Used for livelock detection in CautiousWaitingPolicy.
     * O(1) lookup using HashSet.
     */
    public boolean contains(Transaction txn) {
        return txnSet.contains(txn);
    }

    /**
     * Calculate exponential backoff delay based on retry count.
     * 
     * @param retryCount  The number of retry attempts
     * @param baseDelay The base delay in milliseconds
     * @param maxRetryCap The maximum retry count to cap at (prevents overflow)
     * @return The backoff delay in milliseconds (baseDelay * 2^cappedRetryCount)
     */
    public static long calculateBackoffDelay(int retryCount, long baseDelay, int maxRetryCap) {
        int cappedRetryCount = Math.min(retryCount, maxRetryCap);
        return baseDelay * (1L << cappedRetryCount);
    }

    /**
     * Calculate exponential backoff delay for lock retry operations.
     * Uses 500ms base delay with cap at 4 retries (max 8000ms = 8 seconds).
     * 
     * Backoff progression: 500ms, 1000ms, 2000ms, 4000ms, 8000ms (capped)
     * 
     * @param retryCount The number of lock retry attempts
     * @return The backoff delay in milliseconds
     */
    /** Scale applied to BOTH base delays; 1.0 is the historical 500 ms. Instance state rather
     * than a static, so two queues in one JVM cannot disagree and a test cannot leak a value
     * into the next run - the ID_GENERATOR lesson applied to a policy
     * constant. The no-arg constructor keeps 1.0, so every existing caller is unchanged. */
    private final double backoffScale;

    public long lockRetryBackoff(int retryCount) {
        return Math.max(1L, (long) (calculateLockRetryBackoff(retryCount) * backoffScale));
    }

    public long abortRetryBackoff(int retryCount) {
        return Math.max(1L, (long) (calculateAbortRetryBackoff(retryCount) * backoffScale));
    }

    public static long calculateLockRetryBackoff(int retryCount) {
        // 500ms expressed in the base unit. Was a bare 500 tuned against the millisecond
        // scale - after the microsecond migration that silently became 500us, a 1000x
        // more aggressive retry, which collapsed throughput 6x with no compile error and
        // no test naming it. Exactly the unitless-constant class the migration audit
        // flagged as the quiet one.
        return calculateBackoffDelay(retryCount, 500_000, 4);
    }

    /**
     * Calculate exponential backoff delay for abort retry operations.
     * Uses 500ms base delay with cap at 8 retries (max 128000ms = 128 seconds).
     * 
     * NOTE FOR NEW EXPERIMENTS: To properly simulate extreme Thrashing for NO_WAIT policies, 
     * this value can be temporarily changed to 2000ms. Left at 500ms by default 
     * to preserve the baseline results of the original experiments.
     * 
     * @param retryCount The number of abort retry attempts
     * @return The backoff delay in milliseconds
     */
    public static long calculateAbortRetryBackoff(int retryCount) {
        return calculateBackoffDelay(retryCount, 500_000, 8);
    }

    /**
     * Enqueue transaction with lock retry backoff: 500 ms base, cap at 4 retries, times the
     * queue's backoffScale. The javadoc here said "5ms base, cap at 8" - wrong in BOTH
     * numbers, against calculateLockRetryBackoff's 500_000 / 4 two methods above. Corrected
     * rather than left, because this constant governs 92% of transaction lifetime at high skew
     * and a reader checking the policy reads this line, not the arithmetic.
     */
    public void enqueueLockRetry(Transaction txn, long currentTime, int retryCount) {
        long backoffDelay = lockRetryBackoff(retryCount);
        txn.setEarliestRetryTime(currentTime + backoffDelay);
        heap.offer(txn);
        txnSet.add(txn);
    }

    /**
     * Enqueue transaction with abort retry backoff: 500 ms base, cap at 8 retries, times the
     * queue's backoffScale. Also corrected - it claimed "10s base, cap at 10" against an
     * actual 500_000 / 8. Three different values were documented for two constants, which is
     * how 500 ms survived unexamined long enough to become 25x the service time it retries.
     */
    public void enqueueAbortRetry(Transaction txn, long currentTime, int retryCount) {
        long backoffDelay = abortRetryBackoff(retryCount);
        txn.setEarliestRetryTime(currentTime + backoffDelay);
        heap.offer(txn);
        txnSet.add(txn);
    }

}
