package de.tum.tssimulator.execution;

import de.tum.tssimulator.bookkeeping.BookkeeperTransaction;
import de.tum.tssimulator.core.ExecutionPhase;
import de.tum.tssimulator.core.Transaction;
import de.tum.tssimulator.core.TransactionEvent;
import de.tum.tssimulator.core.TransactionGroup;
import de.tum.tssimulator.core.TransactionState;
import de.tum.tssimulator.core.TransactionStateCounter;
import de.tum.tssimulator.execution.StepContext;
import de.tum.tssimulator.model.IOModel;
import de.tum.tssimulator.monitoring.DebugConfig;
import de.tum.tssimulator.monitoring.DispatchStats;
import de.tum.tssimulator.monitoring.SimulationLogger;
import de.tum.tssimulator.queue.CentralQueueManager;
import de.tum.tssimulator.queue.QueueManager;
import de.tum.tssimulator.queue.QueueType;
import de.tum.tssimulator.scheduling.Scheduler;

/**
 * Abstract base class for admission controllers.
 * Provides common admission control logic shared between ACID and BASE modes.
 * 
 * Common responsibilities:
 * - Backoff queue processing (ABORTED only)
 * - Transaction fetching with priority (READY > PENDING)
 * - Capacity checking (maxConcurrent enforcement)
 * - Dispatch loop and budget management
 * - Event-driven capacity wait management
 * 
 * Subclasses implement mode-specific admission logic:
 * - ACID: C2PL preclaim lock checking before admission
 * - BASE: Optimistic admission (no lock checking)
 */
public abstract class AbstractAdmissionController implements PhaseDispatcher {
    protected final Scheduler scheduler;
    protected final TransactionProcessor processor;
    protected final IOModel ioModel;
    protected final SimulationLogger logger;
    protected final PhaseDispatcher downstreamPhases;
    protected final QueueManager queueManager;
    protected final CapacityWaitManager capacityWaitManager;
    protected StepContext stepContext;
    protected int maxConcurrent = Integer.MAX_VALUE;
    
    // Debug instrumentation (null if disabled)
    protected final DispatchStats dispatchStats;

    public AbstractAdmissionController(Scheduler scheduler,
                                      TransactionProcessor processor,
                                      IOModel ioModel,
                                      SimulationLogger logger,
                                      PhaseDispatcher downstreamPhases,
                                      int maxConcurrent,
                                      QueueManager queueManager) {
        this.scheduler = scheduler;
        this.processor = processor;
        this.ioModel = ioModel;
        this.logger = logger;
        this.downstreamPhases = downstreamPhases;
        this.queueManager = queueManager;
        this.capacityWaitManager = new CapacityWaitManager(this);
        this.maxConcurrent = maxConcurrent;
        this.dispatchStats = DebugConfig.ENABLE_STALL_TRACKING ? new DispatchStats() : null;
    }
    
    public void setStepContext(StepContext context) {
        this.stepContext = context;
    }

    public StepContext getStepContext() {
        return stepContext;
    }
    
    public void setProcessorCapacity(int capacity) {
        this.maxConcurrent = capacity;
    }
    
    public DispatchStats getDispatchStats() {
        return dispatchStats;
    }
    
    public CapacityWaitManager getCapacityWaitManager() {
        return capacityWaitManager;
    }
    
    protected long getCurrentVirtualTime() {
        return stepContext != null ? stepContext.virtualTime : 0;
    }
    
    protected QueueType getQueueForPhase(ExecutionPhase phase) {
        return switch (phase) {
            case LOCK -> QueueType.LOCK;
            case IO -> QueueType.IO;
            case CPU -> QueueType.CPU;
            case ROLLBACK -> QueueType.ROLLBACK;
            case COMPLETE -> QueueType.WAITING_FOR_GROUP;
            default -> QueueType.PENDING;
        };
    }

    @Override
    public long dispatch(long budget, long virtualTime) {
        long currentTime = getCurrentVirtualTime();
        CentralQueueManager centralQueue = (CentralQueueManager) queueManager;
        centralQueue.setCurrentTime(currentTime);
        
        // Move transactions from backoff queues to active queues
        processBackoffQueues(currentTime, centralQueue);

        // Liveness fix: re-attempt any admission waiter that's been parked past the timeout,
        // independent of whether the lock it's registered on ever releases - see
        // AdmissionWaitManager.sweepExpiredWaiters()'s javadoc. No-op where admissionWaitManager
        // isn't wired (default implementation below).
        sweepExpiredAdmissionWaits(currentTime);

        // Check if there's work to do
        if (!hasWorkToDo()) return 0;
        
        // Process transactions until budget exhausted
        return processTransactionLoop(budget, currentTime, centralQueue, virtualTime);
    }
    
    // ============ Common Helper Methods ============
    
    /**
     * Process backoff queues. ABORTED transactions in exponential backoff go back to PENDING
     * (a fresh restart). S2PL soft-denied transactions in lock-retry backoff (see Transaction.
     * softDenyRetryCount's javadoc) go BLOCKED -> READY and into READY_TXN - the transition
     * TransactionLifecycleManager.retrySpecificTransactions() performs immediately; here it is
     * deferred until real virtual time has elapsed (dequeueReadyLockRetryBackoff() only
     * returns a transaction once currentTime >= its scheduled retry time), which is what
     * prevents the same-tick BLOCKED<->READY bounce this queue exists to fix.
     */
    protected void processBackoffQueues(long currentTime, CentralQueueManager centralQueue) {
        Transaction ready;
        while ((ready = centralQueue.dequeueReadyAbortedBackoff(currentTime)) != null) {
            // Four-way lineage time decomposition: leaving BACKOFF, back into PENDING_QUEUE.
            ready.markPhase(Transaction.LineagePhase.PENDING_QUEUE, currentTime);
            queueManager.enqueue(ready, QueueType.PENDING);
        }
        Transaction lockRetryReady;
        while ((lockRetryReady = centralQueue.dequeueReadyLockRetryBackoff(currentTime)) != null) {
            if (lockRetryReady.getState() == TransactionState.BLOCKED) {
                lockRetryReady.transitionTo(TransactionEvent.LOCKS_AVAILABLE);
            }
            // Bug fix: this transaction's delay has genuinely elapsed - the whole point of
            // pushing it here was to make it wait before its NEXT real attempt, not to defer it
            // again. Without this flag, ACIDAdmissionController.admitReadyTransaction() has no
            // way to distinguish this release from any other READY_TXN dequeue, sees
            // softDenyRetryCount still > 0 (it only resets on eventual success), and pushes it
            // straight back into backoff without ever calling processor.process() again - a
            // livelock, confirmed via VictimTraceLog (see Transaction.readyForBackoffRetry's
            // javadoc).
            lockRetryReady.markReadyForBackoffRetry();
            queueManager.enqueue(lockRetryReady, QueueType.READY_TXN);
        }
    }

    /** Default no-op: only the subclasses that actually wire an AdmissionWaitManager (ACID mode)
     * override this. See AdmissionWaitManager.sweepExpiredWaiters()'s javadoc for what this is
     * fixing - a registered waiter otherwise has no bounded time to its next attempt. */
    protected void sweepExpiredAdmissionWaits(long currentTime) {
        // No-op by default.
    }

    protected boolean hasWorkToDo() {
        return !queueManager.isEmpty(QueueType.READY_TXN) || scheduler.hasReady();
    }
    
    // NO ITERATION CAP ON THE ADMISSION LOOP, deliberately. A flat 1000-iteration cap is sized
    // for small configs and routinely hit at higher concurrency: raising it to 10000 eliminates
    // every hit at MPL=32 and changes completion counts, so such a cap truncates legitimate work
    // rather than catching livelock. Scaling the cap by maxConcurrent is the same arbitrary
    // number, just harder to see. Every branch of the loop below was traced, including the one
    // exception worth naming (ACIDAdmissionController.tryAdmitWithLockCheck's check-then-acquire
    // race retry): every branch either dequeues-and-disposes a transaction (admits it, hands it to
    // capacityWaitManager, or discards an invalid one) or hits a natural break condition
    // (fetchNextTransaction()==null, capacity limit). The race retry re-enqueues to PENDING, but
    // the set of transactions that could ever win that race against it is fixed and finite at
    // loop entry (nothing is added to the queues mid-loop - CapacityWaitManager.wakeNext() is
    // only called from outside this loop), so even that path resolves within a bounded number of
    // extra iterations rather than spinning. There is no code path where this loop can iterate
    // without the total work bounded by the queue sizes at loop entry, so an iteration-count cap
    // can never distinguish a big legitimate burst from a real hang - it was never a livelock
    // detector. Genuine hang detection already exists at the right layer - SimulationWatchdog,
    // which watches for lack of progress ACROSS simulation steps using the actual progress
    // signal (completed+aborted counts), not an iteration proxy within one dispatch() call.
    // THE LOOP IS NOT GATED ON A TIME BUDGET EITHER. Gating it on
    // timeUsed < budget accumulates EVERY admitted transaction's lock-acquisition cost
    // (IOModel.getLockAcquisitionTime(), ~300*k for the TGS one-lock-per-resource config) into a
    // single SHARED per-step budget, checked BEFORE each admission (so the last admission before
    // the check could still overshoot the budget by up to one attempt's cost). That treats
    // admission processing as one serial resource with a step-sized clock, throttling NEW
    // admissions per FIXED-length step to budget/(avg lock-acquisition cost) regardless of how
    // many transactions were actually eligible - confirmed empirically to 6 decimal places as the
    // exact mechanism behind every throughput ceiling observed in the TGS Stage 0 recon (k=1 ->
    // exactly 4 admissions/1200ms used; k=5 -> exactly 1 admission/1500ms used; stepTimeSlice
    // =10000 -> throughput=3.400000, exactly 34/10).
    //
    // CompositePhaseDispatcher.dispatch() (LOCK/IO/CPU phase processors) has the identical
    // shape, and resolves it the same way: each processor unit gets the FULL time budget (no
    // shared depletion across units) and the dispatcher returns the
    // MAXIMUM time used, since simulated time advances by the slowest unit running in parallel,
    // not the sum of every unit's cost. Ported here: each admission attempt gets the full budget
    // (no shared timeUsed gate), the loop drains every available PENDING/READY transaction this
    // step (bounded by hasWorkToDo()/maxConcurrent capacity exactly as the pre-existing
    // iteration-cap-removal javadoc above already established - the total work is bounded by the
    // queue sizes at loop entry regardless), and the return value is the MAXIMUM single
    // admission's lock-acquisition cost rather than their sum.
    //
    // lockIOTimePerResource/lockCPUTimePerResource are a separate question from the budget
    // coupling above and are deliberately not touched here, so the two cannot be confused.
    protected long processTransactionLoop(long budget, long currentTime, CentralQueueManager centralQueue, long virtualTime) {
        long maxUsed = 0;
        int loopCount = 0;

        while (hasWorkToDo()) {
            loopCount++;

            // Sample runnable queue size periodically
            if (dispatchStats != null && loopCount % DebugConfig.QUEUE_SIZE_SAMPLE_INTERVAL == 0) {
                int runnableSize = (!queueManager.isEmpty(QueueType.READY_TXN) ? queueManager.size(QueueType.READY_TXN) : 0) +
                                   (scheduler.hasReady() ? 1 : 0);
                dispatchStats.recordRunnableQueueSize(runnableSize);
            }

            Transaction txn = fetchNextTransaction();
            if (txn == null) {
                if (dispatchStats != null) dispatchStats.recordNoWorkReason("QUEUE_EMPTY");
                break;
            }

            if (!isValidTransaction(txn)) continue;

            // Process based on state
            if (txn.getState() == TransactionState.READY) {
                if (!processReadyTransaction(txn, currentTime, centralQueue)) {
                    continue;
                }
            } else {
                // PENDING transaction
                long lockTime = processPendingTransaction(txn);
                if (lockTime < 0) {
                    if (dispatchStats != null) dispatchStats.recordNoWorkReason("CAPACITY_LIMIT");
                    break;
                }
                maxUsed = Math.max(maxUsed, lockTime);
            }
        }

        return maxUsed;
    }
    
    /**
     * Fetch next transaction with priority: READY > PENDING.
     * READY transactions already have locks and are highest priority.
     */
    protected Transaction fetchNextTransaction() {
        // Priority 1: READY transactions (already have locks)
        if (!queueManager.isEmpty(QueueType.READY_TXN)) {
            Transaction txn = queueManager.dequeue(QueueType.READY_TXN);
            if (txn != null) return txn;
        }
        
        // Priority 2: Fresh PENDING transactions
        if (scheduler.hasReady()) {
            return scheduler.nextTransaction();
        }
        
        return null;
    }
    
    protected boolean isValidTransaction(Transaction txn) {
        if (txn.getGroup() == null) {
            if (logger != null) {
                logger.log("[WARN] AbstractAdmissionController: Skipping transaction without group: id=" + txn.getId());
            }
            return false;
        }
        return true;
    }
    
    protected boolean processReadyTransaction(Transaction txn, long currentTime, CentralQueueManager centralQueue) {
        int currentRunning = TransactionStateCounter.getCount(TransactionState.RUNNING);
        
        if (!canAdmitReadyTransaction(txn, currentRunning)) {
            capacityWaitManager.registerReadyWaiter(txn.getGroup());
            if (dispatchStats != null) dispatchStats.recordNoWorkReason("CAPACITY_LIMIT");
            return false;
        }
        
        // Admit to execution
        admitReadyTransaction(txn);
        if (dispatchStats != null) dispatchStats.transactionsAdmitted++;
        return true;
    }
    
    protected boolean canAdmitReadyTransaction(Transaction txn, int currentRunning) {
        // Bookkeepers whose parent is RUNNING can bypass capacity
        if (txn instanceof BookkeeperTransaction) {
            BookkeeperTransaction bk = (BookkeeperTransaction) txn;
            if (bk.getRoot().getState() == TransactionState.RUNNING) {
                return true;
            }
        }
        
        return currentRunning < maxConcurrent;
    }
    
    protected void admitReadyTransaction(Transaction txn) {
        // Skip if already RUNNING
        if (txn.getState() == TransactionState.RUNNING) {
            return;
        }
        
        txn.transitionTo(TransactionEvent.CAPACITY_AVAILABLE);
        QueueType targetQueue = getQueueForPhase(txn.getPhase());
        queueManager.enqueue(txn, targetQueue);
    }
    
    /**
     * Enqueue READY transaction group to their phase queues.
     * Called by CapacityWaitManager when capacity becomes available.
     */
    public void admitReadyTransactionGroup(TransactionGroup group) {
        // Delegates to admitReadyTransaction() rather than duplicating its transition+enqueue
        // logic inline, so ACIDAdmissionController's override - which routes a
        // still-LOCK-phase transaction through a real reacquisition attempt instead of blindly
        // resuming the LOCK-phase timer queue - covers this call site too. See that override's
        // javadoc for why: LOCKS_AVAILABLE (BLOCKED -> READY) is the only event that ever
        // produces READY state in ACID mode, so every group reaching this method (via
        // CapacityWaitManager, after a prior lock-retry wake) needs the same treatment as the
        // per-transaction admission path.
        Transaction parent = group.getParent();
        if (parent != null) {
            admitReadyTransaction(parent);
            if (dispatchStats != null) dispatchStats.transactionsAdmitted++;
        }
        for (BookkeeperTransaction bk : group.getBookkeepers()) {
            admitReadyTransaction(bk);
            if (dispatchStats != null) dispatchStats.transactionsAdmitted++;
        }
    }
    
    /**
     * Enqueue all members of a PENDING transaction group back to PENDING queue.
     * Called by CapacityWaitManager when capacity becomes available.
     */
    public void enqueuePendingTransactionGroup(TransactionGroup group) {
        Transaction parent = group.getParent();
        if (parent != null) {
            queueManager.enqueue(parent, QueueType.PENDING);
        }
        for (BookkeeperTransaction bk : group.getBookkeepers()) {
            queueManager.enqueue(bk, QueueType.PENDING);
        }
    }
    
    protected boolean canAdmitPendingTransaction(Transaction txn, int currentRunning) {
        // Bookkeepers whose parent is RUNNING can bypass capacity
        if (txn instanceof BookkeeperTransaction) {
            BookkeeperTransaction bk = (BookkeeperTransaction) txn;
            if (bk.getRoot().getState() == TransactionState.RUNNING) {
                return true;
            }
        }
        
        return currentRunning < maxConcurrent;
    }
    
    // ============ Template Method for Subclasses ============
    
    /**
     * Process a PENDING transaction attempting admission.
     * Subclasses implement mode-specific logic:
     * - ACID: C2PL preclaim lock checking
     * - BASE: Optimistic admission (no locks)
     * 
     * @param txn Transaction to process
     * @return Lock acquisition time (0 for BASE), or -1 if at capacity
     */
    protected abstract long processPendingTransaction(Transaction txn);
}
