package de.tum.tssimulator.lifecycle;

import de.tum.tssimulator.bookkeeping.BookkeeperTransaction;
import de.tum.tssimulator.core.Transaction;
import de.tum.tssimulator.core.TransactionEvent;
import de.tum.tssimulator.core.TransactionGroup;
import de.tum.tssimulator.core.TransactionGroupState;
import de.tum.tssimulator.core.TransactionState;
import de.tum.tssimulator.core.VictimTraceLog;
import de.tum.tssimulator.execution.CapacityWaitManager;
import de.tum.tssimulator.locking.core.LockManager;
import de.tum.tssimulator.monitoring.SimulationLogger;
import de.tum.tssimulator.queue.QueueManager;
import de.tum.tssimulator.queue.QueueType;
import de.tum.tssimulator.scheduling.TransactionScheduler;

/**
 * Coordinates the abort process for transaction groups.
 * Extracted from TransactionGroup to follow Single Responsibility Principle.
 * 
 * Responsibilities:
 * - Orchestrate multi-step abort process
 * - Coordinate state transitions, lock releases, queue cleanup, and restart
 */
public class TransactionGroupAbortCoordinator {
    
    /**
     * Abort all transactions in a group and restart.
     * Note: Group should clear transactions from processors before calling this.
     */
    public void abortGroup(TransactionGroup group,
                          QueueManager queueManager,
                          LockManager lockManager,
                          TransactionScheduler scheduler,
                          long currentTime,
                          SimulationLogger logger,
                          CapacityWaitManager capacityWaitManager) {
        // Release the group's processor slot. Keyed on the GROUP, not on unit state: a member
        // aborted while waiting between IO and CPU is `current` on no unit, so a unit-scoped
        // release could never fire and the slot would stay held forever -- wedging every later
        // sibling, which surfaces as a watchdog trip rather than a wrong number.
        if (group != null) {
            group.releaseProcessorUnconditionally();
        }

        if (scheduler == null) {
            throw new IllegalArgumentException("Scheduler is required for aborting transactions");
        }
        
        if (group.getState() == TransactionGroupState.ABORTED
                || group.getState() == TransactionGroupState.ABORTING) {
            return;
        }
        
        // Defensive: If group already completed, silently return (race condition - group finished between check and abort)
        if (group.getState() == TransactionGroupState.COMPLETED) {
            return;
        }

        group.setState(TransactionGroupState.ABORTING);

        // Step 1: Remove from ALL queues FIRST (before transitioning to ABORTED)
        // This prevents race conditions where ABORTED transactions remain in queues
        removeFromQueues(group, queueManager);
        
        // Also remove from capacity wait manager queues (READY/PENDING waiters)
        if (capacityWaitManager != null) {
            capacityWaitManager.removeGroup(group);
        }
        
        // Step 2: Queue rollback work. Actual ABORTED transition, lock release,
        // restart, and cleanup happen when rollback consumes CPU time.
        enqueueRollback(group, queueManager);
    }

    public void completeRollback(TransactionGroup group,
                                 QueueManager queueManager,
                                 LockManager lockManager,
                                 TransactionScheduler scheduler,
                                 long currentTime,
                                 SimulationLogger logger,
                                 CapacityWaitManager capacityWaitManager) {
        // Release the group's processor slot. Keyed on the GROUP, not on unit state: a member
        // aborted while waiting between IO and CPU is `current` on no unit, so a unit-scoped
        // release could never fire and the slot would stay held forever -- wedging every later
        // sibling, which surfaces as a watchdog trip rather than a wrong number.
        if (group != null) {
            group.releaseProcessorUnconditionally();
        }

        if (group != null && group.getParent() != null) {
            VictimTraceLog.log("COMPLETE_ROLLBACK_FIRED", group.getParent().getId(),
                    "bookkeeperCount=" + group.getBookkeepers().size());
            for (BookkeeperTransaction bk : group.getBookkeepers()) {
                VictimTraceLog.log("COMPLETE_ROLLBACK_FIRED", bk.getId(),
                        "role=bookkeeper state=" + bk.getState() + " phase=" + bk.getPhase());
            }
        }
        if (group == null || group.getState() == TransactionGroupState.ABORTED) {
            return;
        }
        if (scheduler == null) {
            throw new IllegalArgumentException("Scheduler is required for rollback completion");
        }

        group.setState(TransactionGroupState.ABORTED);

        if (logger != null) {
            logger.metrics().incrementGroupsAborted();
            int bookkeeperCount = group.getBookkeepers().size();
            if (bookkeeperCount > 0) {
                logger.metrics().incrementBookkeepersAborted(bookkeeperCount);
            }
            Transaction parent = group.getParent();
            if (parent != null) {
                logger.metrics().recordRollback(parent.getSimulatedRollbackTime());
            }
        }

        transitionMembersToAborted(group, logger);
        releaseLocks(group, lockManager, logger, currentTime);
        restartTransaction(group, queueManager, scheduler, currentTime, logger);
        // F5 (VLDB reviewer response): restarts_total - every abort today unconditionally
        // restarts the parent (see restartTransaction() above), so this currently tracks 1:1
        // with groupsAbortedTotal, but is kept as its own counter for when that changes (e.g. a
        // future retry cap).
        if (logger != null) {
            logger.metrics().incrementRestartsTotal();
        }

        if (capacityWaitManager != null) {
            capacityWaitManager.removeGroup(group);
            capacityWaitManager.wakeNext();
        }

        cleanupGroup(group);
    }

    private void enqueueRollback(TransactionGroup group, QueueManager queueManager) {
        Transaction parent = group.getParent();
        if (parent != null) {
            parent.transitionTo(TransactionEvent.ROLLBACK_QUEUED);
            queueManager.enqueue(parent, QueueType.ROLLBACK);
        }
    }
    
    private void transitionMembersToAborted(TransactionGroup group, SimulationLogger logger) {
        Transaction parent = group.getParent();
        if (parent != null && parent.getState() != TransactionState.ABORTED) {
            recordWastedWork(parent, logger);
            parent.transitionTo(TransactionEvent.ABORTED);
        }

        // Abort all bookkeepers in this group
        for (BookkeeperTransaction bk : group.getBookkeepers()) {
            if (bk.getState() != TransactionState.ABORTED) {
                recordWastedWork(bk, logger);
                bk.transitionTo(TransactionEvent.ABORTED);
            }

            // DEFENSIVE: Recursively check for child bookkeepers
            // This should not be needed since BookkeeperTransaction constructor
            // uses parent.group, ensuring all bookkeepers at all depths are in
            // the same group. However, this provides safety against future bugs.
            abortChildBookkeepersDefensively(bk, group, logger);
        }
    }

    /**
     * wasted_cpu_io_seconds: right before a member becomes unreachable via ABORTED, its
     * already-accumulated IO/CPU time (TransactionMetrics) would otherwise simply never be
     * read. Only recorded here - the sole point where an about-to-abort member's simulated
     * time is still readable.
     */
    private void recordWastedWork(Transaction member, SimulationLogger logger) {
        if (logger == null || member == null) {
            return;
        }
        logger.metrics().recordWastedIoTime(member.getSimulatedIOTime());
        logger.metrics().recordWastedCpuTime(member.getSimulatedCPUTime());
        // Same call site, for the same reason: this is the only point at which an about-to-abort
        // member's progress is still readable. Turns wasted work per abort from a scalar into a
        // distribution over how far the transaction actually got.
        logger.metrics().recordCursorProgressAtAbort(member.cursorProgress());
        // Same call site again, and for the same one-chance reason. The SPLIT matters: an inlined
        // arm has no CUs at all, so pooling parent and CU discards would compare a single number
        // against the sum of two different populations.
        logger.metrics().recordDiscardedServiceAtAbort(
                member instanceof BookkeeperTransaction,
                member.getSimulatedIOTime(), member.getSimulatedCPUTime(),
                member.getOperationCursor());
    }
    
    /**
     * Defensively check if a bookkeeper has child bookkeepers in a different group.
     * This should NOT happen in normal operation since BookkeeperTransaction 
     * constructor passes parent.group, ensuring all depths share the same group.
     * This is defensive programming to catch potential future bugs.
     */
    private void abortChildBookkeepersDefensively(BookkeeperTransaction parentBk, TransactionGroup rootGroup,
                                                   SimulationLogger logger) {
        // Check if this bookkeeper incorrectly has its own separate group
        TransactionGroup childGroup = parentBk.getGroup();

        if (childGroup != null && childGroup != rootGroup) {
            // BUG DETECTED: Bookkeeper has separate group (should not happen!)
            // Abort them defensively to prevent inconsistent state
            for (BookkeeperTransaction grandchild : childGroup.getBookkeepers()) {
                if (grandchild.getState() != TransactionState.ABORTED) {
                    recordWastedWork(grandchild, logger);
                    grandchild.transitionTo(TransactionEvent.ABORTED);
                }
                // Recursively check deeper levels
                abortChildBookkeepersDefensively(grandchild, rootGroup, logger);
            }
        }
    }

    private void releaseLocks(TransactionGroup group, LockManager lockManager, SimulationLogger logger,
                               long currentTime) {
        Transaction parent = group.getParent();
        if (parent != null) {
            recordLockHoldOnRelease(parent, logger, currentTime);
            recordLockWaitOnAbort(parent, logger, currentTime);
            lockManager.releaseLocks(parent);
        }

        for (BookkeeperTransaction bk : group.getBookkeepers()) {
            recordLockHoldOnRelease(bk, logger, currentTime);
            recordLockWaitOnAbort(bk, logger, currentTime);
            lockManager.releaseLocks(bk);
        }
    }

    /**
     * F5 lock_wait_p50/p95/p99, abort path: a member blocked mid-wait
     * (lockWaitStart set) whose GROUP then aborts would otherwise never have that wait interval
     * recorded at all - TransactionLockCoordinator.acquireAllLocks() only calls recordLockWait()
     * on its SUCCESS branch, and this abort path released locks without ever touching
     * lockWaitStart. In an abort-heavy regime the members that waited longest are
     * disproportionately likely to end up in an aborted group (that's often WHY the group
     * aborted), so silently dropping their wait time meant lock_wait_p* could read artificially
     * low, or even zero, in exactly the high-contention regime you most want visibility into -
     * confirmed empirically: BookkeeperCoverageAudit (deleted) showed
     * lockWaitSampleCount=0 at R=1.2, MPL=8, peakBlockedQueueDepth 390-502, groupsCompletedTotal
     * 9-16 of ~2300-2900 bookkeepers created. Mirrors recordLockHoldOnRelease() immediately
     * above: record the wait time actually incurred up to the abort, using currentTime (an
     * aborted member never reaches a successful acquisition to time against).
     */
    private void recordLockWaitOnAbort(Transaction member, SimulationLogger logger, long currentTime) {
        if (logger == null || member == null || member.getLockWaitStart() < 0) {
            return;
        }
        long waitDuration = Math.max(0L, currentTime - member.getLockWaitStart());
        logger.metrics().recordLockWait(waitDuration);
        member.clearLockWaitTracking();
    }

    /**
     * lock_seconds_waiting_room, abort path: same accumulation as the normal-completion
     * release points (ACIDCompletionHandler/AcidGroupCompletionPolicy.completeGroupAtomically),
     * but here currentTime is threaded in from completeRollback's caller rather than reusing
     * completionTime (an aborted member was never "completed").
     */
    private void recordLockHoldOnRelease(Transaction member, SimulationLogger logger, long currentTime) {
        if (logger == null || member == null || member.getHeldBatches().isEmpty()) {
            return;
        }
        boolean viaWaitingRoom = member.getWaitingRoomEntry() >= 0;
        // Per batch, as on the commit path - an aborted transaction released everything it held at
        // the same moment, so each batch's hold runs from its own acquisition to this abort.
        for (Transaction.HeldBatch batch : member.getHeldBatches()) {
            logger.metrics().recordLockHold(
                    batch.lockCount(), Math.max(0L, currentTime - batch.acquire()), viaWaitingRoom);
        }
        member.clearLockHoldTracking();
    }
    
    private void removeFromQueues(TransactionGroup group, QueueManager queueManager) {
        Transaction parent = group.getParent();
        if (parent != null) {
            VictimTraceLog.log("ABORT_REMOVE_FROM_QUEUES", parent.getId(),
                    "role=parent state=" + parent.getState() + " phase=" + parent.getPhase());
            queueManager.remove(parent);
        }

        for (BookkeeperTransaction bk : group.getBookkeepers()) {
            VictimTraceLog.log("ABORT_REMOVE_FROM_QUEUES", bk.getId(),
                    "role=bookkeeper state=" + bk.getState() + " phase=" + bk.getPhase());
            queueManager.remove(bk);
        }
    }
    
    private void restartTransaction(TransactionGroup group,
                                   QueueManager queueManager,
                                   TransactionScheduler scheduler,
                                   long currentTime,
                                   SimulationLogger logger) {
        Transaction parent = group.getParent();
        if (parent != null) {
            Transaction newTxn = scheduler.restartTransaction(parent);
            int retryCount = newTxn.getLockRetryCount();

            // retryCount is carried forward and incremented across restarts
            // (TransactionFactory.restartTransaction()) even though the transaction gets a
            // fresh id each time - sampling it here gives a starvation/livelock-vs-collective-
            // thrashing histogram without needing a stable per-lineage identity.
            if (logger != null) {
                logger.metrics().recordRestartRetryCount(retryCount);
            }
            // TransactionFactory.restartTransaction() creates a
            // BRAND-NEW TransactionGroup for newTxn (new TransactionGroup(), not this group) -
            // an earlier attempt at per-group max-retryCount tracking here recorded onto the OLD
            // group object, which gets abandoned immediately after this call and never
            // contributes to any future observation. TransactionGroup is NOT stable across
            // restarts, despite what an earlier comment on this method claimed without checking
            // TransactionFactory first. The retryCount that matters for a later completed/
            // censored observation is newTxn.getLockRetryCount() itself, read directly at
            // observation time (ACIDCompletionHandler / SimulationRunner.captureOutcome()) -
            // no per-group accumulation needed.

            // Kaplan-Meier censoring: mark THIS group superseded -
            // newTxn belongs to a brand-new TransactionGroup (see TransactionFactory.
            // restartTransaction()), so this one is now permanently abandoned. SimulationRunner.
            // captureOutcome()'s end-of-run censored-observation sweep uses this flag (not
            // parent.getState(), which can't distinguish "abandoned" from "mid-backoff, will
            // retry") to decide which still-registered groups are genuinely still open.
            group.markSuperseded();

            // Enqueue with backoff (implementation decides behavior)
            queueManager.enqueueWithBackoff(newTxn, currentTime, retryCount);
        }
    }
    
    private void cleanupGroup(TransactionGroup group) {
        group.clearBookkeepers();
        group.resetCompletedCount();
    }
}
