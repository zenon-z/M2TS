package de.tum.tssimulator.locking.coordinator;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import de.tum.tssimulator.bookkeeping.BookkeeperTransaction;
import de.tum.tssimulator.core.Resource;
import de.tum.tssimulator.core.Transaction;
import de.tum.tssimulator.core.TransactionEvent;
import de.tum.tssimulator.core.TransactionGroup;
import de.tum.tssimulator.core.TransactionGroupState;
import de.tum.tssimulator.core.TransactionState;
import de.tum.tssimulator.core.program.Operation;
import de.tum.tssimulator.execution.CapacityWaitManager;
import de.tum.tssimulator.locking.core.LockGranularityMapper;
import de.tum.tssimulator.locking.core.LockManager;
import de.tum.tssimulator.locking.core.LockType;
import de.tum.tssimulator.locking.core.OperationLockModes;
import de.tum.tssimulator.locking.policy.HardDenyDecision;
import de.tum.tssimulator.locking.policy.LockDecision;
import de.tum.tssimulator.locking.policy.SoftDenyDecision;
import de.tum.tssimulator.monitoring.LogEvent;
import de.tum.tssimulator.monitoring.SimulationLogger;
import de.tum.tssimulator.queue.CentralQueueManager;
import de.tum.tssimulator.queue.QueueManager;
import de.tum.tssimulator.queue.QueueType;
import de.tum.tssimulator.scheduling.TransactionScheduler;

/**
 * Base coordinator: everything protocol-AGNOSTIC (collecting lock IDs, dispatching requests,
 * hard-deny/abort handling, the deadlock-prevention cascade). The two protocol-dependent
 * decisions - whether a soft-denied transaction releases what it already holds, and whether a
 * force-blocked group member (not the direct requester) does too - are abstract, implemented by
 * C2PLTransactionLockCoordinator and S2PLTransactionLockCoordinator respectively. See
 * LockingProtocol's javadoc for why this replaced a single class with an
 * ENABLE_C2PL-mutable-static-flag-plus-txn.getPhase()==LOCK conditional: that conditional applied
 * uniformly regardless of the flag, which is what let the S2PL arm silently run C2PL-with-retry
 * semantics for an entire session's worth of experiments (dynamic-footprint-variance-
 * investigation memory).
 */
public abstract class TransactionLockCoordinator {
    private final LockManager lockManager;
    private final QueueManager queueManager;
    private final SimulationLogger logger;
    private final LockGranularityMapper granularityMapper;
    private TransactionScheduler scheduler;  // Non-final to allow wiring after construction
    private CapacityWaitManager capacityWaitManager;

    public TransactionLockCoordinator(LockManager lockManager,
            QueueManager queueManager,
            SimulationLogger logger,
            LockGranularityMapper granularityMapper,
            TransactionScheduler scheduler) {
        this.lockManager = lockManager;
        this.queueManager = queueManager;
        this.logger = logger;
        this.granularityMapper = granularityMapper;
        this.scheduler = scheduler;
    }

    public void setScheduler(TransactionScheduler scheduler) {
        this.scheduler = scheduler;
    }

    public void setCapacityWaitManager(CapacityWaitManager capacityWaitManager) {
        this.capacityWaitManager = capacityWaitManager;
    }
    
    public LockManager getLockManager() {
        return lockManager;
    }

    public LockGranularityMapper getGranularityMapper() {
        return granularityMapper;
    }

    private boolean enableDeadlockPreventionCascade = true;

    public void setEnableDeadlockPreventionCascade(boolean enabled) {
        this.enableDeadlockPreventionCascade = enabled;
    }

    /**
     * Acquire the next batch of locks for {@code txn}.
     *
     * <p>Base implementation IS {@link #acquireAllLocks} - the whole footprint, one batch, cursor
     * untouched. Deliberately a delegation rather than the {@code UnsupportedOperationException} the
     * plan called for: the FIRST acquisition also comes through here (from
     * {@code ACIDStrategy.process}), so C2PL must be able to make this call. Delegating means that
     * at {@code operationBatchSize = 0}, and under C2PL at any setting, this is literally the old
     * call - which is what makes the interleaving path inert by construction rather than by a
     * protocol conditional that could drift.
     */
    public boolean acquireBatchLocks(Transaction txn) {
        return acquireAllLocks(txn);
    }

    /**
     * Requests the folded locks for {@code operations} only - the batch variant of
     * {@link #acquireAllLocks}, sharing its denial handling and hold-tracking so an interleaved
     * batch behaves exactly like a whole-footprint acquisition that happens to be smaller.
     */
    protected boolean acquireOperations(Transaction txn, List<Operation> operations) {
        // Always a new batch: each cursor advance is a distinct acquisition at a distinct time.
        return acquireRequested(txn,
                OperationLockModes.foldOperations(operations, granularityMapper), operations.size(), true);
    }

    public boolean acquireAllLocks(Transaction txn) {
        Map<String, LockType> requested = OperationLockModes.foldByLockId(txn, granularityMapper);
        return acquireRequested(txn, requested, requested.size(), txn.getHeldBatches().isEmpty());
    }

    /**
     * Shared body: request every entry of {@code requested} in iteration order, handling denial and
     * seeding lock-hold tracking on success.
     *
     * @param lockCountForHold locks attributed to this acquisition for the hold metric. For a whole
     *        footprint that is the request size; for one batch of an interleaved acquisition the
     *        batch's own size, so per-batch accounting stays additive rather than last-batch-wins.
     * @param newBatch whether this acquisition appends a hold-tracking batch. False for a repeat
     *        whole-footprint acquisition of an already-tracked transaction - a C2PL transaction is
     *        preclaimed at admission and then re-requests the same locks through
     *        ACIDStrategy.process, and counting that twice would double its lock-hold
     *        contribution. Equivalent to seeding only when not already tracked.
     */
    private boolean acquireRequested(Transaction txn, Map<String, LockType> requested,
            int lockCountForHold, boolean newBatch) {
        for (Map.Entry<String, LockType> entry : requested.entrySet()) {
            String lockId = entry.getKey();
            logger.logEvent(new LogEvent(LogEvent.Type.LOCK_REQUESTED, txn.getId(), 0, lockId));
            LockDecision decision = lockManager.requestLock(txn, lockId, entry.getValue());
            if (!decision.granted()) {
                return handleLockDenial(txn, lockId, decision);
            }
        }

        long currentTime = (queueManager instanceof CentralQueueManager cqm) ? cqm.getCurrentTime() : 0L;

        // Seed lock-hold tracking HERE, at the point locks are actually acquired, rather than
        // leaving it to the admission path. ACIDAdmissionController.tryAdmitWithLockCheck() was the
        // only place that ever called setLockHoldStart, and under S2PL that method returns at its
        // protocol branch BEFORE reaching the seeding - so no S2PL parent was ever tracked, and
        // getMeanLockHoldDurationPerLock read a flat zero for the entire S2PL arm. That is the
        // arm the operation-level ladder's hold-time criterion gates on.
        //
        // Guarded on "not already tracked" so this cannot clobber a C2PL preclaim seed with a later
        // timestamp and understate the hold. First acquisition wins, which is the correct reading
        // of when a hold begins.
        if (newBatch) {
            txn.recordBatchAcquired(lockCountForHold, currentTime);
        }

        if (txn.getLockWaitStart() >= 0) {
            logger.metrics().recordLockWait(Math.max(0L, currentTime - txn.getLockWaitStart()));
            txn.clearLockWaitTracking();
        }
        return true;
    }

    /**
     * Protocol-dependent: does the DIRECT requester release what it already holds when
     * soft-denied? C2PL: always (atomic all-or-nothing - a partial grab that can't complete is
     * abandoned). S2PL: never (hold-and-wait - keep everything already acquired, block only for
     * the specific lock that was denied). No phase check either way - see LockingProtocol's
     * javadoc for why a phase-gated version of this was the actual bug.
     */
    protected abstract boolean shouldReleaseOnSoftDeny(Transaction txn);

    /**
     * Protocol-dependent: does a force-blocked group member (NOT the direct requester - see
     * blockGroupMember()) release what it already holds? C2PL: yes, mirroring
     * shouldReleaseOnSoftDeny() - nothing survives a block event, keeping the protocol internally
     * consistent. S2PL: no - true hold-and-wait applies uniformly to every group member, not just
     * the direct requester.
     */
    protected abstract boolean shouldReleaseForceBlockedMember();

    private boolean handleLockDenial(Transaction txn, String lockId, LockDecision decision) {
        logger.logEvent(new LogEvent(LogEvent.Type.LOCK_DENIED, txn.getId(), 0, lockId));

        // Release locks based on decision type and protocol (see shouldReleaseOnSoftDeny()):
        // 1. HardDenyDecision (abort): Always release locks
        // 2. SoftDenyDecision (block): protocol-dependent, no phase check
        if (decision instanceof HardDenyDecision) {
            lockManager.releaseLocks(txn);
            abortHardDeniedGroup(txn, lockId);
            return false;
        } else if (decision instanceof SoftDenyDecision) {
            if (shouldReleaseOnSoftDeny(txn)) {
                lockManager.releaseLocks(txn);
                // This transaction has given up everything it held and will re-run
                // acquireAllLocks() from scratch on wake - a real retry cycle with no other
                // counter (restartsTotal is full-group-abort only, a different code path). See
                // SimulationMetrics.softDenyReleaseCyclesTotal's field comment.
                logger.metrics().incrementSoftDenyReleaseCyclesTotal();
            }
        }

        // Handle the denial
        decision.handle(txn, lockId, lockManager, queueManager, logger);

        // For SoftDenyDecision: block the entire group (requester already blocked by
        // decision.handle())
        // For HardDenyDecision: group already aborted, no need to block
        if (decision instanceof SoftDenyDecision) {
            blockEntireTransactionGroup(txn, lockId);

            // NEW RULE: After blocking this group, check if this group was holding locks
            // that other transactions were waiting for. Abort those waiters to prevent
            // deadlock.
            if (enableDeadlockPreventionCascade) {
                abortWaitersBlockedByGroup(txn);
            }
        }

        return false;
    }

    private void abortHardDeniedGroup(Transaction txn, String lockId) {
        TransactionGroup group = txn.getGroup();
        if (group == null) {
            return;
        }
        if (group.getState() == TransactionGroupState.COMPLETED
                || group.getState() == TransactionGroupState.ABORTED) {
            return;
        }
        long currentTime = (queueManager instanceof CentralQueueManager)
                ? ((CentralQueueManager) queueManager).getCurrentTime()
                : 0;
        logger.logEvent(new LogEvent(LogEvent.Type.LOCK_DENIED, txn.getId(), 0,
                "resource=" + lockId + " decision=hard-deny"));
        group.abortAll(queueManager, lockManager, scheduler, currentTime, logger, capacityWaitManager);
        logger.logEvent(new LogEvent(LogEvent.Type.TRANSACTION_ABORTED, txn.getId(), 0,
                "name=aborted delta=1"));
        logger.metrics().incrementAborted();
        logger.metrics().recordAbortReason("cautious_wait");
    }
    
    private void blockEntireTransactionGroup(Transaction txn, String lockId) {
        TransactionGroup group = txn.getGroup();
        if (group == null) return;
        
        // If the group is already aborted, don't try to block its members
        if (group.getState() == TransactionGroupState.ABORTED) {
            return;
        }

        // Clear all group members from active processors before blocking
        group.blockAll(logger);

        // Get all group members (parent + bookkeepers)
        List<Transaction> members = group.getAllMembers();

        // Block all members except the requester (already blocked by decision.handle()) - see
        // shouldReleaseForceBlockedMember() for whether they release what they already hold
        // (protocol-dependent, no longer a comment claiming one fixed answer for both protocols).
        for (Transaction member : members) {
            if (member == txn)
                continue; // Already blocked by decision.handle()

            blockGroupMember(member, lockId);
        }
    }

    private void blockGroupMember(Transaction member, String lockId) {
        // Check state before attempting any FSM transition
        TransactionState state = member.getState();
        
        // ASSERTION: COMPLETED transactions should never be blocked (bug if this happens)
        if (state == TransactionState.COMPLETED) {
            throw new IllegalStateException("BUG: Cannot block COMPLETED transaction " + 
                member.getId() + " - all work finished and locks released");
        }

        // Skip if already in BLOCKED or ABORTED state
        if (state == TransactionState.BLOCKED ||
            state == TransactionState.ABORTED) {
            // Already blocked or aborted - skip
            return;
        }

        // Only transition if in a valid state for LOCK_DENIED event
        // Valid states: RUNNING, PENDING (not BLOCKED, COMPLETED, ABORTED)
        if (state == TransactionState.RUNNING ||
            state == TransactionState.PENDING) {
            // Remove from ALL queues first to avoid being in multiple queues
            if (queueManager instanceof CentralQueueManager) {
                ((CentralQueueManager) queueManager).remove(member);
            }
            
            member.transitionTo(TransactionEvent.LOCK_DENIED);

            if (shouldReleaseForceBlockedMember()) {
                lockManager.releaseLocks(member);
            }

            // F5 (VLDB reviewer response): lock_wait_p50/p95/p99 - mark the wait start for this
            // sibling too (mirrors SoftDenyDecision.handle()'s marking for the original requester).
            if (member.getLockWaitStart() < 0) {
                long waitStartTime = (queueManager instanceof CentralQueueManager cqm) ? cqm.getCurrentTime() : 0L;
                member.setLockWaitStart(waitStartTime);
            }

            // CRITICAL FIX: Register this sibling in lockWaiters so it gets retried when locks are released
            // This is the same lock that the original requester was blocked on
            lockManager.registerWaiter(member, lockId);

            // BLOCKED phase opens HERE, at the enqueue, not at the transitionTo above - the
            // interval this measures is "sitting in BLOCKED_TXN", and the state transition and
            // the enqueue are separated by a releaseLocks() and a registerWaiter() that belong
            // to the member's previous phase. Parent-only, matching every other markPhase call
            // site: phasePopulation is a parent population and the MPL gate assumes exactly that.
            if (!(member instanceof BookkeeperTransaction)) {
                member.markBlocked(
                        (queueManager instanceof CentralQueueManager cqm) ? cqm.getCurrentTime() : 0L);
            }
            queueManager.enqueue(member, QueueType.BLOCKED_TXN);
        }
        // Else: transaction is in some other state, don't force a transition
    }

    private void abortWaitersBlockedByGroup(Transaction txn) {
        // When we block a transaction group, we need to abort any transactions
        // that were waiting for locks held by this group
        // This prevents: T1 holds lock, T2 waits for T1, then T1 gets blocked → deadlock
        
        TransactionGroup group = txn.getGroup();
        if (group == null) return;
        
        // Get all members of the blocked group
        List<Transaction> blockedMembers = group.getAllMembers();
        
        // Find all transactions waiting for locks held by any member of this group
        Set<TransactionGroup> groupsToAbort = new HashSet<>();
        
        for (Transaction blockedMember : blockedMembers) {
            // Get all transactions waiting for this blocked member
            Set<Transaction> waiters = lockManager.getWaitersForTransaction(blockedMember);
            if (waiters != null) {
                for (Transaction waiter : waiters) {
                    if (waiter.getGroup() != null) {
                        groupsToAbort.add(waiter.getGroup());
                    }
                }
            }
        }

        // Abort all the waiting groups
        for (TransactionGroup groupToAbort : groupsToAbort) {
            if (groupToAbort.getState() != TransactionGroupState.ABORTED) {
                logger.logEvent(new LogEvent(LogEvent.Type.TRANSACTION_ABORTED, 
                    groupToAbort.getParent().getId(), 0, 
                    "reason=holder-blocked name=cascade-abort delta=1"));
                long currentTime = (queueManager instanceof CentralQueueManager)
                    ? ((CentralQueueManager) queueManager).getCurrentTime() : 0;
                groupToAbort.abortAll(queueManager, lockManager, scheduler, currentTime, logger, capacityWaitManager);
                logger.metrics().incrementAborted();
                // Track abort reason
                logger.metrics().recordAbortReason("holder_blocked_cascade");
            }
        }
    }

    public void releaseAllLocks(Transaction txn) {
        // Same reachability as the cascade-abort path above. The fallback is the SENTINEL, not 0:
        // a non-CentralQueueManager really has no clock to read here, and 0 would be a lie that
        // reads as a timestamp.
        long releaseTime = (queueManager instanceof CentralQueueManager)
            ? ((CentralQueueManager) queueManager).getCurrentTime() : LogEvent.NO_VIRTUAL_TIME;
        for (Resource resource : txn.getResources()) {
            logger.logEvent(new LogEvent(LogEvent.Type.LOCK_RELEASED, txn.getId(), releaseTime,
                "release:" + resource));
        }
        lockManager.releaseLocks(txn);
    }
}
