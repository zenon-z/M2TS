package de.tum.tssimulator.queue;

import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;

import de.tum.tssimulator.bookkeeping.BookkeeperTransaction;
import de.tum.tssimulator.core.ExecutionPhase;
import de.tum.tssimulator.core.Transaction;
import de.tum.tssimulator.core.VictimTraceLog;
import de.tum.tssimulator.execution.CompositePhaseDispatcher;
import de.tum.tssimulator.model.IOModel;
import de.tum.tssimulator.scheduling.TransactionScheduler;

public class CentralQueueManager implements QueueManager {
    // Threading model:
    // Queue mutation is owned by the single simulation loop. These Deque-backed
    // queues are not intended for concurrent access across worker threads.
    private final Map<QueueType, Deque<Transaction>> queues = new EnumMap<>(QueueType.class);

    // Unit affinity (low-N
    // crossover mechanism fix): IO/CPU only. Once a TransactionGroup is pinned to a specific
    // SimulatedProcessorUnit (TransactionGroup.pinUnitIfAbsent(), written from
    // SimulatedProcessorUnit.assign()), every later member of that group is routed directly to
    // that unit's own deque here instead of the shared `queues` deque above - so a group's own
    // members can no longer spread across multiple units simultaneously, which is what let the
    // S2PL-dynamic arm's bookkeeper cascade get real (and confound-inducing) cross-unit
    // parallelism the monolithic S2PL-oracle control could never have (see
    // BookkeeperParallelismCheck, S2PLOracleAbortMechanismCheck's sibling). This is AFFINITY,
    // not a dedicated processor: the same unit keeps pulling unrelated groups' first arrivals
    // from the shared `queues` deque and gets pinned by them too, so a pinned group's later
    // members may still queue behind unrelated work on their own unit - only cross-unit
    // spreading WITHIN one group is eliminated, which is the only thing the fix needs.
    // Populated lazily via initPooledQueues(), called once per pooled type from
    // DefaultExecutionBundle at the same point that type's SimulatedProcessorUnit pool is
    // sized, so index i here always corresponds to unit i in that pool.
    private final Map<QueueType, List<Deque<Transaction>>> pooledOwnQueues = new EnumMap<>(QueueType.class);

    // Cached aggregate views so size()/isEmpty() (per type) and contains()/move()/remove() (all
    // types) don't materialize a fresh list on every call - those three run on abort/block/
    // cleanup hot paths, and with 700+700 units a per-call ArrayList over every deque would be
    // real allocation pressure there. Pool sizes are fixed once initPooledQueues() runs for a
    // type, so these are built incrementally at init time (constructor + initPooledQueues()) and
    // never rebuilt afterward.
    private final Map<QueueType, List<Deque<Transaction>>> combinedByType = new EnumMap<>(QueueType.class);
    private final List<Deque<Transaction>> allDequesCache = new ArrayList<>();
    private BackoffQueue abortedBackoffQueue = new BackoffQueue();  // For restarted transactions
    // For soft-denied S2PL transactions retrying lock acquisition - see
    // Transaction.softDenyRetryCount's javadoc for why the retry has to be paced at all.
    private BackoffQueue lockRetryBackoffQueue = new BackoffQueue();
    private TransactionScheduler transactionScheduler;
    private final IOModel ioModel;  // Required for phase preparation
    public long currentTime = 0;  // Updated by dispatcher each step
    
    // Phase dispatchers for clearing transactions from processor units
    private final List<CompositePhaseDispatcher> phaseDispatchers = new ArrayList<>();
    
    /** Apply the configured backoff scale to both queues. Called once at wiring time; 1.0 is a
     * no-op, which is what keeps the default bit-identical. */
    public void setBackoffScale(double scale) {
        this.abortedBackoffQueue = new BackoffQueue(scale);
        this.lockRetryBackoffQueue = new BackoffQueue(scale);
    }

    public CentralQueueManager(IOModel ioModel) {
        if (ioModel == null) {
            throw new IllegalArgumentException("IOModel is required for QueueManager");
        }
        this.ioModel = ioModel;
        for (QueueType type : QueueType.values()) {
            queues.put(type, new LinkedList<>());
        }
        for (QueueType type : QueueType.values()) {
            List<Deque<Transaction>> combined = new ArrayList<>();
            combined.add(queues.get(type));
            combinedByType.put(type, combined);
        }
        allDequesCache.addAll(queues.values());
    }

    /**
     * Set up unit-affinity storage for a pooled QueueType (IO or CPU) - called once per type
     * from DefaultExecutionBundle, right where the corresponding SimulatedProcessorUnit pool is
     * sized, so unitCount always matches that pool's size exactly (a unit's own index, passed to
     * dequeueOwn() below, is a direct index into this list). Idempotent guard: throws rather
     * than silently reinitializing, since a second call would orphan whatever work is already
     * sitting in the first list's deques.
     */
    public void initPooledQueues(QueueType type, int unitCount) {
        if (pooledOwnQueues.containsKey(type)) {
            throw new IllegalStateException("Pooled queues already initialized for " + type);
        }
        List<Deque<Transaction>> pooled = new ArrayList<>(unitCount);
        for (int i = 0; i < unitCount; i++) {
            pooled.add(new LinkedList<>());
        }
        pooledOwnQueues.put(type, pooled);
        combinedByType.computeIfAbsent(type, t -> new ArrayList<>()).addAll(pooled);
        allDequesCache.addAll(pooled);
    }
    
    /**
     * Maps queue types to execution phases.
     * 
     * ONLY map queues that represent actual execution units consuming time via IOModel:
     * - CPU, IO, RECONCILIATION
     * 
     * Meta-queues (PENDING, LOCK, etc.) explicitly return null.
     * These are coordination/scheduling queues, not execution phases.
     * 
     * No default case: forces compile-time exhaustiveness check.
     * If a new QueueType is added, compiler will require explicit handling.
     */
    private ExecutionPhase phaseFor(QueueType qt) {
        return switch (qt) {
            // Execution queues - correspond to processors that consume time
            case CPU -> ExecutionPhase.CPU;
            case IO -> ExecutionPhase.IO;
            case ROLLBACK -> ExecutionPhase.ROLLBACK;
            case RECONCILIATION -> ExecutionPhase.RECONCILIATION;
            
            // Meta-queues - coordination/scheduling, not execution phases
            case PENDING, ADMISSION_WAIT, BLOCKED_TXN, READY_TXN, LOCK,
                 AWAITING_USER_ACTION, WAITING_FOR_GROUP -> null;
        };
    }
    
    /**
     * Register a phase dispatcher so transactions can be cleared from processor units.
     * Call this after creating each CompositePhaseDispatcher (LOCK, IO, CPU).
     */
    public void registerPhaseDispatcher(CompositePhaseDispatcher dispatcher) {
        phaseDispatchers.add(dispatcher);
    }
    
    public BackoffQueue getAbortedBackoffQueue() {
        return abortedBackoffQueue;
    }

    public Transaction dequeueReadyAbortedBackoff(long currentTime) {
        return abortedBackoffQueue.dequeueReady(currentTime);
    }

    public int abortedBackoffSize() {
        return abortedBackoffQueue.size();
    }

    public BackoffQueue getLockRetryBackoffQueue() {
        return lockRetryBackoffQueue;
    }

    public Transaction dequeueReadyLockRetryBackoff(long currentTime) {
        return lockRetryBackoffQueue.dequeueReady(currentTime);
    }

    public void enqueueLockRetryBackoff(Transaction txn, long currentTime, int retryCount) {
        lockRetryBackoffQueue.enqueueLockRetry(txn, currentTime, retryCount);
    }

    public int lockRetryBackoffSize() {
        return lockRetryBackoffQueue.size();
    }

    public long getCurrentTime() {
        return currentTime;
    }
    
    public void setCurrentTime(long time) {
        this.currentTime = time;
    }
    
    public void setTransactionScheduler(TransactionScheduler scheduler) {
        this.transactionScheduler = scheduler;
    }
    
    public TransactionScheduler getTransactionScheduler() {
        return transactionScheduler;
    }
    
    @Override
    public void enqueue(Transaction txn, QueueType queueType) {
        VictimTraceLog.log("ENQUEUE", txn.getId(), "queueType=" + queueType
                + " phase=" + txn.getPhase() + " state=" + txn.getState());
        // Prepare phase before enqueueing (centralized responsibility)
        ExecutionPhase phase = phaseFor(queueType);
        if (phase != null) {
            // Only prepare if transaction isn't already in target phase (idempotency guard)
            if (txn.getPhase() != phase) {
                txn.preparePhase(phase, ioModel);
            }
        }

        // Unit affinity (IO/CPU only) - see pooledOwnQueues' field comment and
        // TransactionGroup.pinUnitIfAbsent()'s javadoc. A pinned group's member goes straight to
        // its unit's own queue, bypassing the shared pool entirely. Unpinned work (a group's
        // first arrival at this phase - normally the parent) falls through to the existing
        // shared-deque path below, unchanged.
        // groupUnitAffinity=false makes a group's members ordinary pool work, dispatched to
        // whatever unit is free - a different model of what a cascade IS (independent transactions
        // rather than continuations of the parent), not a tuning knob. The pin is still WRITTEN by
        // SimulatedProcessorUnit.assign(); leaving that unconditional keeps the flag to one site,
        // and an unread pin has no effect. This is the ONLY read of getPinnedUnitIndex() in
        // production code, so gating here is complete - the unit-affinity dequeue below drains
        // pooledOwnQueues, which nothing reaches when this branch is skipped.
        List<Deque<Transaction>> pooled = pooledOwnQueues.get(queueType);
        if (pooled != null && txn.getGroup() != null && ioModel.getConfig().groupUnitAffinity) {
            int pin = txn.getGroup().getPinnedUnitIndex(queueType);
            if (pin >= 0) {
                VictimTraceLog.log("ENQUEUE_AFFINITY", txn.getId(),
                        "queueType=" + queueType + " unitIndex=" + pin);
                pooled.get(pin).addLast(txn);
                return;
            }
        }

        // Guard against uninitialized queue (defensive programming)
        Deque<Transaction> queue = queues.get(queueType);
        if (queue == null) {
            throw new IllegalStateException("Queue not initialized: " + queueType);
        }
        queue.addLast(txn);
    }

    /**
     * Unit-affinity dequeue (IO/CPU only) - see pooledOwnQueues' field comment. Called by
     * SimulatedProcessorUnit.dequeueNext() BEFORE falling back to the shared dequeue(type), so a
     * unit always drains its own pinned work first. Returns null if this unit has nothing of its
     * own queued right now (empty deque, or the type isn't pooled) - the caller's fallback to
     * the shared pool handles both cases identically.
     */
    public Transaction dequeueOwn(QueueType type, int unitIndex, long virtualTime, long stepEnd) {
        List<Deque<Transaction>> pooled = pooledOwnQueues.get(type);
        if (pooled == null || unitIndex < 0 || unitIndex >= pooled.size()) {
            return null;
        }
        // SCAN rather than poll the head: this deque can hold members of several groups, and a
        // head whose group is busy in the other pool must not stall the rest. Returns the first
        // member whose group has no other member on an IO/CPU unit right now.
        Deque<Transaction> own = pooled.get(unitIndex);
        Transaction t = null;
        for (Iterator<Transaction> it = own.iterator(); it.hasNext(); ) {
            Transaction cand = it.next();
            if (ioModel.getConfig().groupUnitAffinity && type != QueueType.LOCK
                    && cand.getGroup() != null
                    && cand.getGroup().isProcessorBusyForOther(cand)) {
                continue;
            }
            // Readiness: a transaction whose previous phase ended at or after this step's end
            // cannot run in this step. Skipping it here -- rather than dequeuing, re-enqueuing
            // and breaking -- keeps its queue position and lets the unit serve a ready member of
            // another group instead of idling for the rest of the step.
            if (cand.getReadyAt() >= stepEnd) {
                continue;
            }
            it.remove();
            t = cand;
            break;
        }
        if (t != null) {
            VictimTraceLog.log("DEQUEUE_AFFINITY", t.getId(),
                    "queueType=" + type + " unitIndex=" + unitIndex);
        }
        return t;
    }

    public void enqueueAll(Iterable<Transaction> ts, QueueType type) { for (Transaction t : ts) { VictimTraceLog.log("ENQUEUE_ALL", t.getId(), "queueType=" + type); queues.get(type).addLast(t); } }
    public void enqueueAll(List<Transaction> ts, QueueType type) { for (Transaction t : ts) { VictimTraceLog.log("ENQUEUE_ALL", t.getId(), "queueType=" + type); queues.get(type).addLast(t); } }
    /**
     * Scanning dequeue for the SHARED pool: returns the first transaction that is both ready
     * within this step and whose group holds no other IO/CPU unit.
     *
     * <p>The shared queue holds unpinned work from every group -- notably every parent on its
     * LOCK->IO transition -- so a head-of-line not-ready transaction would stall ready parents of
     * OTHER groups behind it. Dequeue-then-requeue also moves the transaction to the tail every
     * step it is not ready, reordering it behind later arrivals. Scanning avoids both, and
     * applying the occupancy predicate here closes the gap the invariant otherwise catches only
     * by throwing.
     */
    public Transaction dequeueFirstEligible(QueueType type, long virtualTime, long stepEnd) {
        Deque<Transaction> q = queues.get(type);
        if (q == null) {
            return null;
        }
        for (Iterator<Transaction> it = q.iterator(); it.hasNext(); ) {
            Transaction cand = it.next();
            if (cand.getReadyAt() >= stepEnd) {
                continue;
            }
            if (ioModel.getConfig().groupUnitAffinity && type != QueueType.LOCK
                    && cand.getGroup() != null
                    && cand.getGroup().isProcessorBusyForOther(cand)) {
                continue;
            }
            it.remove();
            VictimTraceLog.log("DEQUEUE_ELIGIBLE", cand.getId(),
                    "queueType=" + type);
            return cand;
        }
        return null;
    }

    /**
     * How many queued entries are READY to start before {@code stepEnd} - the work-conservation
     * audit's denominator.
     *
     * <p>Separate from {@link #size}, and the distinction is the whole point of the audit: an idle
     * unit beside a NON-EMPTY queue is fine if nothing in that queue is ready yet, because the
     * work genuinely cannot start. An idle unit beside a READY entry is not. Uses the same
     * {@code getReadyAt() >= stepEnd} predicate {@link #dequeueFirstEligible} skips on, so the two
     * cannot disagree about what "ready" means.
     *
     * <p>Counts the shared deque AND the per-unit affinity deques, like {@link #size}, so a pinned
     * group's queued members are not invisible here.
     */
    public int countReadyWithin(QueueType type, long stepEnd) {
        int total = 0;
        for (Deque<Transaction> q : combinedByType.get(type)) {
            for (Transaction t : q) {
                if (t.getReadyAt() < stepEnd) {
                    total++;
                }
            }
        }
        return total;
    }

    public Transaction dequeue(QueueType type) {
        Transaction t = queues.get(type).pollFirst();
        if (t != null) {
            VictimTraceLog.log("DEQUEUE", t.getId(), "queueType=" + type
                    + " phase=" + t.getPhase() + " state=" + t.getState());
        }
        return t;
    }
    public boolean isEmpty(QueueType type) { return size(type) == 0; }
    // Sums the shared deque plus any per-unit affinity deques for this type (combinedByType is
    // exactly [sharedDeque] for a non-pooled type, so this is a no-op change in that case) -
    // see pooledOwnQueues' field comment. Without this, a pinned group's queued C would be
    // invisible to any caller reading size(IO)/size(CPU) - traced every current caller (none
    // make an admission/scheduling decision off these two specifically, see
    // dynamic-footprint-variance-investigation memory) but several feed CSV/watchdog-dump
    // reporting (SimulationMetrics.peakIoQueueDepth etc.), which would otherwise silently
    // undercount post-affinity.
    @Override
    public int size(QueueType type) {
        int total = 0;
        for (Deque<Transaction> q : combinedByType.get(type)) {
            total += q.size();
        }
        return total;
    }
    @Override
    public int totalActiveTransactions() {
        // Count all transactions that are active in the system pool
        // Excludes: AWAITING_USER_ACTION (waiting on external input)
        // Excludes: RECONCILIATION (bookkeeper background work)
        return size(QueueType.PENDING)
             + size(QueueType.ADMISSION_WAIT)      // Added - waiting for admission
             + size(QueueType.BLOCKED_TXN)
             + size(QueueType.READY_TXN)
             + size(QueueType.LOCK)
             + size(QueueType.IO)
             + size(QueueType.CPU)
             + size(QueueType.ROLLBACK)
             + size(QueueType.WAITING_FOR_GROUP);
    }

    @Override
    public void prepend(Transaction t, QueueType type) {
        queues.get(type).addFirst(t);
    }
    
    @Override
    public boolean contains(Transaction t) {
        for (Deque<Transaction> q : allDequesCache) {
            if (q.contains(t)) return true;
        }
        return false;
    }

    /**
     * OrphanTransactionAudit's other building block: is this transaction `current` in any
     * registered processor unit right now? Combined with contains(t) above, a RUNNING (or
     * BACKGROUND_WORK) transaction that is in neither has been dropped - not in any queue, not
     * owned by any processor, unreachable by anything that would otherwise advance or terminate
     * it. Non-mutating, O(units) - cheap enough to call from an end-of-run/watchdog-trip audit.
     */
    public boolean isAssignedToAnyProcessor(Transaction t) {
        for (CompositePhaseDispatcher dispatcher : phaseDispatchers) {
            if (dispatcher.isTransactionAssigned(t)) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * Check if a specific queue contains a transaction.
     * Uses Queue.contains() which is O(n) but doesn't modify the queue.
     */
    public boolean contains(Transaction t, QueueType type) {
        for (Deque<Transaction> q : combinedByType.get(type)) {
            if (q.contains(t)) return true;
        }
        return false;
    }
    
    /**
     * Move a transaction from wherever it currently is to a new queue.
     * Removes the transaction from all queues and adds it to the target queue.
     * 
     * @param t The transaction to move
     * @param targetType The target queue type
     * @throws IllegalStateException if transaction is not in any source queue
     */
    public void move(Transaction t, QueueType targetType) {
        boolean found = false;

        // Remove from all queues (shared and per-unit affinity alike - see allDequesCache's
        // field comment)
        for (Deque<Transaction> q : allDequesCache) {
            if (q.remove(t)) {
                found = true;
            }
        }

        // Fail-fast if transaction not found
        if (!found) {
            throw new IllegalStateException(
                "Cannot move transaction " + t.getId() +
                " - not found in any queue. Target was: " + targetType);
        }

        VictimTraceLog.log("MOVE", t.getId(), "targetType=" + targetType
                + " phase=" + t.getPhase() + " state=" + t.getState());
        // Add to target queue
        queues.get(targetType).addLast(t);
    }
    
    /**
     * Remove a transaction from all queues (including backoff queue) AND processor units.
     * Use this when aborting or blocking a transaction to ensure it's fully stopped.
     * 
     * @param t The transaction to remove
     * @return true if transaction was removed from at least one location, false if not found
     */
    @Override
    public boolean remove(Transaction t) {
        boolean removed = false;

        // Remove from all regular queues (shared and per-unit affinity alike - see
        // allDequesCache's field comment)
        for (Deque<Transaction> q : allDequesCache) {
            if (q.remove(t)) {
                removed = true;
            }
        }

        // Remove from backoff queues (for transactions in retry delay)
        if (abortedBackoffQueue.remove(t)) {
            removed = true;
        }
        if (lockRetryBackoffQueue.remove(t)) {
            removed = true;
        }

        // Clear from all processor units (stops mid-execution transactions)
        for (CompositePhaseDispatcher dispatcher : phaseDispatchers) {
            dispatcher.clearTransactionFromProcessors(t);
        }

        if (removed) {
            VictimTraceLog.log("REMOVE", t.getId(), "phase=" + t.getPhase()
                    + " state=" + t.getState());
        }

        return removed;
    }
    
    @Override
    public void enqueueWithBackoff(Transaction txn, long currentTime, int retryCount) {
        // Use existing backoff queue
        abortedBackoffQueue.enqueueAbortRetry(txn, currentTime, retryCount);
    }
    
    /**
     * Count non-bookkeeper transactions in a specific queue.
     * Bookkeepers don't count toward processor limits.
     */
    public int countNonBookkeepers(QueueType type) {
        Deque<Transaction> queue = queues.get(type);
        int count = 0;
        for (Transaction txn : queue) {
            if (!(txn instanceof BookkeeperTransaction)) {
                count++;
            }
        }
        return count;
    }
}
