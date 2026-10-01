package de.tum.tssimulator.execution;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.tum.tssimulator.bookkeeping.BookkeeperTransaction;
import de.tum.tssimulator.core.ExecutionPhase;
import de.tum.tssimulator.core.Transaction;
import de.tum.tssimulator.core.TransactionEvent;
import de.tum.tssimulator.core.TransactionState;
import de.tum.tssimulator.core.TransactionStateCounter;
import de.tum.tssimulator.core.VictimTraceLog;
import de.tum.tssimulator.locking.core.GlobalLockBudget;
import de.tum.tssimulator.locking.core.LockGranularityMapper;
import de.tum.tssimulator.locking.core.LockManager;
import de.tum.tssimulator.locking.core.LockStateView;
import de.tum.tssimulator.locking.core.LockType;
import de.tum.tssimulator.locking.core.OperationLockModes;
import de.tum.tssimulator.locking.impl.BudgetedLockManagerDecorator;
import de.tum.tssimulator.locking.impl.SS2PLLockManager;
import de.tum.tssimulator.locking.policy.DeadlockStrategy;
import de.tum.tssimulator.locking.policy.LockingProtocol;
import de.tum.tssimulator.model.IOModel;
import de.tum.tssimulator.monitoring.SimulationLogger;
import de.tum.tssimulator.queue.CentralQueueManager;
import de.tum.tssimulator.queue.QueueManager;
import de.tum.tssimulator.queue.QueueType;
import de.tum.tssimulator.scheduling.Scheduler;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * ACID admission controller implementing C2PL (Conservative Two-Phase Locking) preclaim.
 * 
 * * ACID Mode Characteristics:
 * - Preclaim: All locks must be available before transaction starts
 * - Event-driven waiting: Uses AdmissionWaitManager to wait for specific locks
 * - Deterministic lock ordering: Prevents deadlock via sorted lock acquisition
 * - Atomic lock acquisition: All-or-nothing lock semantics
 * 
 * Admission Flow:
 * 1. Collect required lock IDs using granularity mapper
 * 2. Check if all locks are available (don't acquire yet)
 * 3. If unavailable, register as waiter on blocking lock (event-driven)
 * 4. If available, atomically acquire all locks
 * 5. Call ACIDStrategy.process() to initialize transaction phases
 * 
 * [UPGRADES FOR S2PL SUPPORT]
 * Historically, this controller implemented C2PL (Conservative Two-Phase
 * Locking) preclaim,
 * where all locks were atomically acquired at admission. However, C2PL
 * mathematically
 * prevents deadlocks from forming, rendering S2PL deadlock prevention
 * strategies
 * (like Wound-Wait, No-Wait, Wait-Die) completely useless and impossible to
 * evaluate.
 * 
 * To accurately evaluate Deadlock Strategies and WFG, the admission logic has
 * been
 * upgraded to support S2PL (Strict Two-Phase Locking). Transactions are now
 * admitted
 * into the execution pipeline where they acquire locks incrementally, allowing
 * real
 * deadlocks to form and policies to trigger correctly.
 */
public class ACIDAdmissionController extends AbstractAdmissionController {
    private static final Logger LOGGER = LoggerFactory.getLogger(ACIDAdmissionController.class);
    // Was a mutable public static (ENABLE_C2PL) with two writers (bootstrap sync + direct pokes
    // from ~20 experiment/diagnostic classes) and a reader (tryAdmitWithLockCheck()) that could
    // silently see either one depending on call order - dual sources of truth on the same
    // question TransactionLockCoordinator's C2PL/S2PL split exists to answer once, in one place.
    // Now an instance field, defaulting to C2PL (unchanged behavior for any caller that doesn't
    // explicitly wire it), set via setLockingProtocol() - see DefaultExecutionBundle, which is
    // the only production call site, sourcing it from SimulatorConfig.lockingProtocol (the same
    // single source of truth DefaultLockingBundle uses to pick the TransactionLockCoordinator
    // subclass). See LockingProtocol's javadoc.
    private LockingProtocol lockingProtocol = LockingProtocol.C2PL;

    public void setLockingProtocol(LockingProtocol lockingProtocol) {
        this.lockingProtocol = lockingProtocol != null ? lockingProtocol
                : LockingProtocol.C2PL;
    }

    private final AdmissionWaitManager admissionWaitManager;
    private LockGranularityMapper granularityMapper;
    private GlobalLockBudget lockBudget;
    private final DeadlockStrategy deadlockStrategy;

    public ACIDAdmissionController(Scheduler scheduler,
            TransactionProcessor processor,
            IOModel ioModel,
            SimulationLogger logger,
            PhaseDispatcher downstreamPhases,
            int maxConcurrent,
            QueueManager queueManager,
            AdmissionWaitManager admissionWaitManager) {
        this(scheduler, processor, ioModel, logger, downstreamPhases, maxConcurrent, queueManager,
                admissionWaitManager, DeadlockStrategy.CAUTIOUS_WAITING);
    }

    public ACIDAdmissionController(Scheduler scheduler,
            TransactionProcessor processor,
            IOModel ioModel,
            SimulationLogger logger,
            PhaseDispatcher downstreamPhases,
            int maxConcurrent,
            QueueManager queueManager,
            AdmissionWaitManager admissionWaitManager,
            DeadlockStrategy deadlockStrategy) {
        super(scheduler, processor, ioModel, logger, downstreamPhases, maxConcurrent, queueManager);
        this.admissionWaitManager = admissionWaitManager;
        this.deadlockStrategy = deadlockStrategy;

        if (processor != null && processor.getLockCoordinator() != null) {
            this.granularityMapper = processor.getLockCoordinator().getGranularityMapper();
        }
    }
    
    @Override
    protected void sweepExpiredAdmissionWaits(long currentTime) {
        if (admissionWaitManager != null) {
            admissionWaitManager.sweepExpiredWaiters(currentTime);
        }
    }

    public void setLockBudget(GlobalLockBudget lockBudget) {
        this.lockBudget = lockBudget;
    }

    public void setGranularityMapper(LockGranularityMapper mapper) {
        this.granularityMapper = mapper;
    }

    /**
     * Retry-path lock reacquisition fix. The base class's admitReadyTransaction() just
     * transitions READY->RUNNING and routes the transaction to whatever queue matches its
     * CURRENT phase - it never calls back into lock acquisition. That's correct for a
     * transaction that's READY purely because of a prior CAPACITY gate (it already holds
     * everything it needs; see TransactionPhaseInitializer.initialize()'s comment - LOCK-phase
     * queueing there only ever happens AFTER a real acquireAllLocks() success). It's wrong for a
     * transaction that reached READY via TransactionEvent.LOCKS_AVAILABLE (BLOCKED -> READY,
     * fired when the lock it was denied on frees up) - LOCKS_AVAILABLE is the ONLY event that
     * ever produces READY state in this FSM, so EVERY READY transaction in ACID mode passed
     * through a prior denial. Such a transaction does NOT actually hold its locks (handleLockDenial()
     * released whatever it had, since txn.getPhase() is still LOCK - it never advanced) - it was
     * about to be routed straight into the LOCK-phase timer queue, which only ever simulates the
     * TIME COST of an acquisition that already succeeded elsewhere, never attempts one itself.
     * Confirmed directly: a 5-simulated-second static/S2PL run left totalWaitersCount=61485 stale
     * entries, 4993/5000 sampled belonging to transactions in state COMPLETED - they completed
     * without their stale wait-tracker registrations (only ever cleared inside requestLock())
     * ever being touched again, because they never called requestLock() again after their one
     * denial.
     *
     * Fix: whenever a READY transaction's phase is still LOCK (i.e. it has not yet had a
     * successful acquireAllLocks() pass reset/confirm that phase), route it through a genuine
     * reacquisition attempt (processor.process(), same call the PENDING path uses) instead of
     * the timer queue. Safe/idempotent for the legitimate capacity-only case too: requestLock()
     * on a lock this transaction (or its group) already holds is a fast, side-effect-free grant.
     *
     * Livelock guard: a transaction that reaches here still holding a nonzero
     * softDenyRetryCount (see its javadoc) already failed a reacquisition attempt through this
     * exact path before - retrying it AGAIN immediately risks a same-tick cascade (this denial
     * releases whatever it partially grabbed, which can eagerly wake another transaction via
     * retrySpecificTransactions(), which can fail and release and wake another, ... with no
     * virtual time ever passing - confirmed directly: 9GB of log output for 5 simulated seconds
     * at mpl=640). retrySpecificTransactions() itself is left untouched (its immediate BLOCKED
     * -> READY transition is a documented, tested contract several other callers/strategies
     * rely on, and is exactly correct for the common single-cycle case) - only a REPEAT offender
     * gets deferred here, via the same exponential-backoff mechanism the abort-restart path
     * uses.
     */
    @Override
    protected void admitReadyTransaction(Transaction txn) {
        if (txn.getState() == TransactionState.RUNNING) {
            return;
        }

        if (txn.getPhase() == ExecutionPhase.LOCK) {
            // Bug fix: a backoff-released transaction must be given a real retry THIS call, not
            // deferred again - consumeReadyForBackoffRetry() is the only thing that can tell this
            // apart from a fresh denial, since softDenyRetryCount alone can't (it stays > 0 until
            // an actual success). See Transaction.readyForBackoffRetry's javadoc for the livelock
            // this fixes.
            boolean forceRetryNow = txn.consumeReadyForBackoffRetry();
            if (!forceRetryNow && txn.getSoftDenyRetryCount() > 0 && queueManager instanceof CentralQueueManager cqm) {
                // Already failed at least once - defer instead of hammering it again this tick.
                // Left in READY state (accurate: it's still waiting for admission, just now
                // also pacing-delayed) - processBackoffQueues() re-enqueues it to READY_TXN
                // once real virtual time has passed, without needing another FSM transition.
                cqm.enqueueLockRetryBackoff(txn, getCurrentVirtualTime(), txn.getSoftDenyRetryCount());
                txn.incrementSoftDenyRetryCount();
                return;
            }

            // Transition READY->RUNNING BEFORE attempting reacquisition, not after: if this
            // attempt is denied again, handleLockDenial() fires TransactionEvent.LOCK_DENIED,
            // which the FSM only allows from PENDING or RUNNING (never READY) - matches the
            // existing "RUNNING (group member blocked)" case the FSM already documents.
            txn.transitionTo(TransactionEvent.CAPACITY_AVAILABLE);
            boolean success = processor.process(txn);
            if (!success) {
                // Denied again - handleLockDenial() (invoked internally by acquireAllLocks())
                // already re-registered/re-blocked/aborted this transaction as appropriate.
                // Mark this cycle as a failure so the NEXT admission attempt (once woken again)
                // takes the throttled backoff path above instead of retrying immediately.
                txn.incrementSoftDenyRetryCount();
                return;
            }
            // Reacquired successfully. ACIDStrategy.process()'s PENDING->RUNNING guard doesn't
            // fire here (already RUNNING from the transition above, not PENDING), and
            // TransactionPhaseInitializer.initialize() already re-enqueued it to QueueType.LOCK
            // on success. Reset the soft-deny backoff counter now that it actually holds its
            // locks - a LATER denial (e.g. a bookkeeper needing an additional lock) should start
            // its own backoff progression from scratch, not inherit whatever this one climbed to.
            txn.resetSoftDenyRetryCount();
            return;
        }

        super.admitReadyTransaction(txn);
    }

    @Override
    protected long processPendingTransaction(Transaction txn) {
        if (deadlockStrategy == DeadlockStrategy.ADAPTIVE) {
            AdaptiveConcurrencyManager.getInstance().recordTxnStart();
        }
        int currentRunning = TransactionStateCounter.getCount(TransactionState.RUNNING);

        // BOOKKEEPERS: Check if additional locks needed beyond parent's locks
        // 1. Bookkeeper Lock Inheritance (Pass-through if locks held)
        if (txn instanceof BookkeeperTransaction) {
            BookkeeperTransaction bk = (BookkeeperTransaction) txn;
            if (bk.getRoot().getState() == TransactionState.RUNNING) {
                // Check if bookkeeper needs locks not held by ANY group member
                boolean needsAdditionalLocks = bookkeeperRequiresAdditionalLocks(bk);
                if (!needsAdditionalLocks) {
                    // Bookkeeper inherits all locks from parent/group - skip lock acquisition
                    boolean success = processor.process(txn);
                    if (dispatchStats != null && success) {
                        dispatchStats.transactionsAdmitted++;
                    }
                    return 0; // No lock time (parent already paid it)
                }
                // Otherwise fall through to standard lock acquisition logic below
                if (logger != null) {
                    logger.log("[BOOKKEEPER-NEEDS-LOCKS] Txn " + txn.getId() + 
                              " needs additional locks beyond parent");
                }
                return processBookkeeperWithBlockingSemantics(bk);
            } else {
                LOGGER.warn("Bookkeeper {} root is not RUNNING; admission is stuck", txn.getId());
                // Return rather than falling through to the capacity check below, which is
                // written for PARENT transactions only. A bookkeeper's txn.getGroup() is the SAME
                // TransactionGroup object as its root/parent - registering it there as a capacity
                // pendingWaiter duplicates whatever lock-wait registration the group already has
                // (e.g. TransactionLockCoordinator.blockGroupMember(), fired when the root itself
                // just got LOCK_DENIED and transitioned to BLOCKED, which is exactly why the root
                // isn't RUNNING here). Two independent wake mechanisms then race to enqueue the
                // same group in the same tick - CapacityWaitManager.wakeNext() (fired by some
                // unrelated group's completeRollback(), not even this one) enqueues it to PENDING
                // for a full from-scratch re-admission, while SS2PLLockManager.releaseLocks()'s
                // waitTracker-based retry enqueues it to READY_TXN via the normal blocked->ready
                // path - landing the same transaction in two queues at once, stomping its phase
                // back to LOCK mid-flight. Confirmed via VictimTraceLog replay
                // (ACIDBookkeeperLockTimingTest#testBookkeepersWithHighSemanticOverlap): T4-RO
                // ended up enqueued to both READY_TXN and PENDING in the same tick, tripping
                // completeGroupAtomically()'s "Parent not at COMPLETE phase" invariant.
                //
                // Correction (still this session): the comment above claimed this bookkeeper's
                // fate was "already fully owned by the group's own block/abort/retry machinery"
                // and needed nothing further - that's wrong. TransactionLockCoordinator.
                // blockGroupMember() only sweeps group.getAllMembers() at the MOMENT a sibling
                // gets denied; a bookkeeper discovered/created AFTER that sweep already ran (this
                // recursive-cascade case) is never caught by it. Plain `return 0` here just
                // dequeues this bookkeeper from PENDING and drops it with no re-enqueue - if every
                // OTHER group member happens to already be phase=COMPLETE (no one left to trigger
                // a future denial/sweep), nothing ever touches it again: a genuine permanent
                // dropped reference. Confirmed via VictimTraceLog replay
                // (TransactionGroupReentrantAbortRegressionTest, seed=1000003): T51's group left
                // exactly 3 bookkeepers stuck at state=PENDING forever this way, so the group's
                // ~35 held locks never released, which every other blocked group in the run was
                // then correctly (not buggily) waiting on.
                //
                // A raw re-enqueue to PENDING is NOT safe either: processTransactionLoop()'s
                // `while (hasWorkToDo())` would very likely re-dequeue and re-hit this exact
                // branch on the same tick with zero virtual time passing - the same same-tick
                // livelock class fixed elsewhere this session (see Transaction.
                // softDenyRetryCount's javadoc).
                //
                // Instead, put this bookkeeper into the SAME BLOCKED_TXN queue blockGroupMember()
                // itself uses - not because it's personally denied a specific lock, but because
                // SS2PLLockManager.releaseLocks()'s retry expansion ("Expand txnsToRetry to
                // include ALL members of affected transaction groups") sweeps in every CURRENT
                // group.getAllMembers() the next time ANY member of this group is woken by a real
                // lock release, regardless of whether each swept member was individually
                // registered as a waiter. Since this bookkeeper's root is blocked precisely
                // because the group has at least one genuinely lock-blocked member already, that
                // future release event is guaranteed to eventually happen and will pick this one
                // up too - real virtual time passes before it's touched again, same as every
                // other blocked group member.
                if (txn.getState() == TransactionState.PENDING) {
                    txn.transitionTo(TransactionEvent.LOCK_DENIED);
                    queueManager.enqueue(txn, QueueType.BLOCKED_TXN);
                }
                return 0;
            }
        }

        // Check capacity before attempting lock acquisition
        // 2. Capacity Check (Pure Admission Control)
        if (!canAdmitPendingTransaction(txn, currentRunning)) {
            capacityWaitManager.registerPendingWaiter(txn.getGroup());
            if (dispatchStats != null)
                dispatchStats.recordNoWorkReason("CAPACITY_LIMIT");
            return -1; // Signal to stop processing
        }
        long lockAttemptTime = ioModel.getLockAcquisitionTime(txn);

        LinkedHashMap<String, LockType> requiredLocks = collectRequiredLockModes(txn);
        if (!tryAdmitWithLockCheck(txn, requiredLocks, currentRunning)) {
            // Waiting for locks or requeued due to race
            return 0;  // Continue processing other transactions
        }

        boolean processSuccess = processor.process(txn);

        // If process returns false (failed to acquire lock), but state became BLOCKED,
        // it means Wait-Die or Wound-Wait strategy is active, which counts as
        // "successfully admitted to queue".
        // If state is ABORTED (No-Wait), it does not count as admitted, need Backoff.
        boolean effectivelyAdmitted = processSuccess || (txn.getState() == TransactionState.BLOCKED);

        if (dispatchStats != null) {
            if (effectivelyAdmitted) {
                dispatchStats.transactionsAdmitted++;
                if (!(txn instanceof BookkeeperTransaction)) {
                    dispatchStats.parentsAdmitted++;
                }
            } else {
                // This counts "Admission Blocked" (denied entry), not "Lock Wait Blocked"
                dispatchStats.transactionsBlocked++;
            }
        }

        if (!effectivelyAdmitted) {
            // Processing failed (e.g. No-Wait triggers Abort).
            // We do NOT charge the dispatcher budget with the backoff time
            // (lockAttemptTime).
            // The transaction pays the penalty in the BackoffQueue, but the system is free
            // to process others.
            return 0;
        }

        // Return 0 to allow rapid admission of next transaction
        // lockAttemptTime should be simulated by the transaction itself (in LOCK
        // phase), not by the dispatcher
        return 0;
    }

    private long processBookkeeperWithBlockingSemantics(BookkeeperTransaction bookkeeper) {
        long lockAttemptTime = ioModel.getLockAcquisitionTime(bookkeeper);
        boolean success = processor.process(bookkeeper);

        // BOOKKEEPER LOCK INSTRUMENTATION, which measures actual instance-level lock footprint
        // rather than the static k_eff_static proxy. Bookkeepers on THIS path
        // (needs-additional-locks, as opposed to the inherited-locks fast path, which acquires
        // zero new locks by design) go through processor.process() -> ACIDStrategy.process() ->
        // TransactionLockCoordinator.acquireAllLocks(), which does not mark
        // lockHoldStart/lockCountHeld - the marking in tryAdmitWithLockCheck() is on the parent
        // path only.
        // Marked HERE, AFTER success (not before, unlike tryAdmitWithLockCheck()'s parent path)
        // because parents' preclaim checks lock availability before attempting (atomicity
        // guaranteed up front), while this path has no such guarantee - acquireAllLocks() can
        // acquire some locks then get denied on a later one, and (per blockEntireTransactionGroup's
        // comment) a bookkeeper keeps whatever partial locks it already got rather than releasing
        // them on denial. Only a successful (true) return means every requested lock was
        // actually granted, so that is the only point at which requiredLockIds.size() is a
        // correct count of what this bookkeeper is genuinely holding.
        if (success) {
            List<String> requiredLockIds = collectRequiredLockIds(bookkeeper);
            bookkeeper.recordBatchAcquired(requiredLockIds.size(), getCurrentVirtualTime());
        }

        if (dispatchStats != null) {
            if (success) {
                dispatchStats.transactionsAdmitted++;
            } else {
                dispatchStats.transactionsBlocked++;
            }
        }

        return success ? lockAttemptTime : lockAttemptTime * 2;
    }
    
    /**
     * Try to admit a transaction with event-driven lock checking.
     * Strategy-Aware Pre-claim Logic.
     * 
     * When ENABLE_C2PL is false (S2PL mode): 
     * Transactions are always admitted so they can hit the LockManager. This allows 
     * deadlocks to actually form and S2PL policies (Wait-Die, Wound-Wait, WFG) to trigger.
     * 
     * When ENABLE_C2PL is true (Legacy mode): 
     * Atomically acquires all locks at the admission gate. Prevents deadlocks mathematically.
     *
     * @param txn Transaction to admit
     * @param requiredLocks Sorted lock id -> mode map (all EXCLUSIVE at this step)
     * @param currentRunning Count of RUNNING transactions at the top of processPendingTransaction
     *                       (TGS static-locking validation's n_running - passed through rather
     *                       than re-read here since nothing mutates the RUNNING count between
     *                       that read and this call on the parent-admission path)
     * @return true if admitted, false if waiting or requeued
     */
    private boolean tryAdmitWithLockCheck(Transaction txn, Map<String, LockType> requiredLocks,
            int currentRunning) {
        if (lockingProtocol == LockingProtocol.S2PL) {
            // S2PL Mode: Always admit. Let the LockManager/Policy handle the decision (Abort, Wait, Wound).
            return true;
        }

        // Track admission attempt
        if (dispatchStats != null) dispatchStats.admissionAttemptsTotal++;

        // TGS static-locking validation: capture attempt-level state BEFORE checkLocksAvailable
        // runs, so it reflects the world this specific attempt actually saw, not the world after
        // its own (possible) acquisition. attemptIndex is read before incrementing so the very
        // first attempt records 0. heldLockUnits comes from the live lock table (only meaningful
        // when the lock manager is SS2PLLockManager, the sole ACID-mode implementation) - but the
        // production wiring (DefaultLockingBundle) always wraps it in a
        // BudgetedLockManagerDecorator, so SS2PLLockManager must be found via getDelegate() (same
        // unwrap SoftDenyDecision already relies on) rather than a direct instanceof check, which
        // would silently and always miss - measured, every row reading heldLockUnits == -1.
        int attemptIndexForThisAttempt = txn.getAdmissionAttemptIndex();
        txn.incrementAdmissionAttemptIndex();
        AdmissionAttemptProvenance provenance = txn.getAdmissionAttemptProvenance();
        int attemptK = requiredLocks.size();
        LockManager lockManagerForAttempt = processor.getLockCoordinator().getLockManager();
        int heldLockUnits = findSS2PLLockManager(lockManagerForAttempt)
                .map(SS2PLLockManager::getLocksHeld)
                .orElse(-1);
        long attemptVirtualTime = getCurrentVirtualTime();
        // TGS static-locking validation: record this transaction's first-ever attempt time, read
        // back at successful admission to compute the first-attempt-to-admitted delay (the direct
        // test of whether blocking costs any virtual time in this engine - see
        // SimulationMetrics.recordAdmissionDelay()'s javadoc).
        if (attemptIndexForThisAttempt == 0) {
            txn.setFirstAdmissionAttemptVirtualTime(attemptVirtualTime);
        }

        // Check if all locks are available (don't acquire yet)
        String blockingLock = lockManagerForAttempt.checkLocksAvailable(txn, requiredLocks);

        if (blockingLock != null) {
            VictimTraceLog.log("ADMIT_BLOCKED_AT_CHECK", txn.getId(),
                    "blockingLock=" + blockingLock);
            // Four-way lineage time decomposition: parent-lineage only, matches maxRetryCount/
            // maxBookkeeperDepth's existing scope - bookkeepers that reach this call site (see
            // the lock_seconds_waiting_room comment below) aren't tracked here.
            if (!(txn instanceof BookkeeperTransaction)) {
                txn.markPhase(Transaction.LineagePhase.ADMISSION_WAIT, attemptVirtualTime);
            }
            // Register waiter on first blocking lock
            if (admissionWaitManager != null) {
                admissionWaitManager.registerWaiter(blockingLock, txn, attemptVirtualTime);
                if (dispatchStats != null) dispatchStats.transactionsBackedOff++;
                if (logger != null) {
                    logger.log("[ADMISSION-WAIT] Txn " + txn.getId() +
                              " waiting for lock: " + blockingLock);
                }
            } else {
                // Fallback: no admission wait manager, requeue. Doesn't set RACE_RETRY/WOKEN -
                // this path is unreachable in production wiring (ACID mode always constructs an
                // AdmissionWaitManager), kept only as a defensive fallback.
                queueManager.enqueue(txn, QueueType.PENDING);
            }
            recordAdmissionAttempt(attemptVirtualTime, attemptK, currentRunning, heldLockUnits,
                    attemptIndexForThisAttempt, provenance, AdmissionOutcome.BLOCKED_AT_CHECK);
            return false;  // Admission failed
        }

        // All locks available - now acquire them atomically
        boolean success = lockManagerForAttempt.tryAcquireAllLocks(txn, requiredLocks);

        if (!success) {
            // Race condition: locks became unavailable between check and acquire (this
            // transaction was neither blocked at check time nor admitted - see AdmissionOutcome's
            // javadoc for why that distinction matters for P1). Rare but possible - requeue to
            // retry, with NO wakeup-batch cap unlike the blocked-at-check path above.
            VictimTraceLog.log("ADMIT_LOST_RACE", txn.getId(),
                    "requeueing to PENDING (uncapped)");
            txn.setAdmissionAttemptProvenance(AdmissionAttemptProvenance.RACE_RETRY);
            if (!(txn instanceof BookkeeperTransaction)) {
                // Net-zero: closes and immediately reopens PENDING_QUEUE, correctly representing
                // the check-then-lost-race as instantaneous rather than a real state change.
                txn.markPhase(Transaction.LineagePhase.PENDING_QUEUE, attemptVirtualTime);
            }
            queueManager.enqueue(txn, QueueType.PENDING);
            if (logger != null) {
                logger.log("[ADMISSION-RACE] Txn " + txn.getId() +
                          " lost race, requeueing");
            }
            recordAdmissionAttempt(attemptVirtualTime, attemptK, currentRunning, heldLockUnits,
                    attemptIndexForThisAttempt, provenance, AdmissionOutcome.LOST_RACE);
            return false;
        }

        // lock_seconds_waiting_room: mark the hold-interval start for this transaction's own
        // lock acquisition. Bookkeepers on the inherited-locks fast path (see
        // processPendingTransaction above) never reach this call site - they're attributed 0
        // additional locks by design, only bookkeepers that acquire their own locks via this
        // same preclaim path get their own hold interval.
        VictimTraceLog.log("ADMIT_ADMITTED", txn.getId(),
                "lockCount=" + requiredLocks.size());

        txn.recordBatchAcquired(requiredLocks.size(), getCurrentVirtualTime());
        if (!(txn instanceof BookkeeperTransaction)) {
            txn.markPhase(Transaction.LineagePhase.RUNNING, attemptVirtualTime);
        }

        recordAdmissionAttempt(attemptVirtualTime, attemptK, currentRunning, heldLockUnits,
                attemptIndexForThisAttempt, provenance, AdmissionOutcome.ADMITTED);
        if (logger != null && txn.getFirstAdmissionAttemptVirtualTime() >= 0) {
            logger.metrics().recordAdmissionDelay(
                    attemptVirtualTime - txn.getFirstAdmissionAttemptVirtualTime());
        }
        return true;  // Successfully admitted
    }

    /**
     * TGS static-locking validation: forwards one admission-attempt observation to the opt-in
     * trace (SimulationMetrics.recordAdmissionAttempt, off by default - see its javadoc). No-op
     * (and no allocation beyond the call itself) when the trace is disabled or logger is null.
     * Provenance/outcome are passed as their enum names() rather than the execution-package enum
     * types themselves, so SimulationMetrics (monitoring package) doesn't need a new dependency
     * on execution - matches the existing one-way execution -> monitoring dependency direction
     * (DispatchStats lives in monitoring but is populated with plain primitives, never execution
     * types).
     */
    private void recordAdmissionAttempt(long virtualTime, int k, int nRunning, int heldLockUnits,
            int attemptIndex, AdmissionAttemptProvenance provenance, AdmissionOutcome outcome) {
        if (logger != null) {
            logger.metrics().recordAdmissionAttempt(virtualTime, k, nRunning, heldLockUnits,
                    attemptIndex, provenance.name(), outcome.name());
        }
    }

    /**
     * TGS static-locking validation: production wiring (DefaultLockingBundle) always wraps
     * SS2PLLockManager in a BudgetedLockManagerDecorator, so a direct
     * {@code instanceof SS2PLLockManager} check on the LockManager returned by
     * getLockCoordinator().getLockManager() silently and always misses - measured, every
     * heldLockUnits value reading -1. Mirrors the unwrap SoftDenyDecision relies on via
     * getDelegate().
     */
    private static Optional<SS2PLLockManager> findSS2PLLockManager(LockManager lockManager) {
        if (lockManager instanceof SS2PLLockManager ss2pl) {
            return Optional.of(ss2pl);
        }
        if (lockManager instanceof BudgetedLockManagerDecorator decorator) {
            return findSS2PLLockManager(decorator.getDelegate());
        }
        return Optional.empty();
    }
    /**
     * Collect the list of unique lock IDs required for this transaction's
     * resources.
     * Uses granularityMapper to map resources to lock IDs.
     */
    private List<String> collectRequiredLockIds(Transaction txn) {
        return OperationLockModes.lockIds(txn, granularityMapper);
    }

    /**
     * The same sorted lock ids as {@link #collectRequiredLockIds}, as the mode map the LockManager
     * now takes.
     *
     * <p>Modes are folded from the transaction's operations (READ -> SHARED, WRITE -> EXCLUSIVE,
     * stronger wins per lock id). At {@code writeFraction = 1.0} every operation is a WRITE, so
     * every entry is EXCLUSIVE and this reduces to the pre-operation-modes behaviour exactly.
     *
     * <p>LinkedHashMap, not a plain map: iteration order is acquisition order, and
     * {@code collectRequiredLockIds} sorts precisely so that order is deterministic.
     */
    private LinkedHashMap<String, LockType> collectRequiredLockModes(Transaction txn) {
        return OperationLockModes.foldByLockIdSorted(txn, granularityMapper);
    }

    /**
     * Check if a bookkeeper requires additional locks beyond what the transaction group already holds.
     * 
     * This method checks the actual lock table to see if any group member (parent or sibling bookkeepers)
     * already holds all the locks this bookkeeper needs.
     * 
     * @param bookkeeper The bookkeeper transaction to check
     * @return true if bookkeeper needs additional locks, false if all locks already held by group
     */
    private boolean bookkeeperRequiresAdditionalLocks(BookkeeperTransaction bookkeeper) {
        if (granularityMapper == null)
            // Conservative: assume needs locks if we can't verify
            return true;
        // Get the lock IDs this bookkeeper needs
        Set<String> bookkeeperLockIds = bookkeeper.getLockIds(granularityMapper);

        // Collect all group members (parent + existing bookkeepers)
        Set<Transaction> groupMembers = new LinkedHashSet<>();
        Transaction root = bookkeeper.getRoot();
        groupMembers.add(root);
        if (root.getGroup() != null) {
            for (BookkeeperTransaction existingBk : root.getGroup().getBookkeepers()) {
                if (existingBk != bookkeeper) { // Don't include self
                    groupMembers.add(existingBk);
                }
            }
        }

        // Get the actual lock manager to check lock table
        LockManager lockManager = processor.getLockCoordinator().getLockManager();
        if (lockManager instanceof LockStateView lockStateView) {
            for (String lockId : bookkeeperLockIds) {
                boolean lockHeldByGroup = false;

                Set<Transaction> lockHolders = lockStateView.getLockHolders(lockId);

                for (Transaction holder : lockHolders) {
                    if (groupMembers.contains(holder)) {
                        lockHeldByGroup = true;
                        break;
                    }
                }

                if (!lockHeldByGroup) {
                    return true;
                }
            }

            return false;
        }
        // Conservative: if we can't check lock table, assume needs locks
        return true;
    }
}
