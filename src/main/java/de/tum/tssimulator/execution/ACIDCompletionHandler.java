package de.tum.tssimulator.execution;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.tum.tssimulator.bookkeeping.BookkeeperTransaction;
import de.tum.tssimulator.core.ExecutionPhase;
import de.tum.tssimulator.core.Transaction;
import de.tum.tssimulator.core.TransactionEvent;
import de.tum.tssimulator.core.TransactionGroup;
import de.tum.tssimulator.core.TransactionState;
import de.tum.tssimulator.lifecycle.TransactionLifecyclePort;
import de.tum.tssimulator.monitoring.DebugConfig;
import de.tum.tssimulator.state.TransactionGroupRegistry;

/**
 * ACID completion handler - atomic group completion.
 * 
 * Parent waits for all bookkeepers to complete, then completes the entire
 * group atomically. This ensures strong consistency and ACID properties.
 * 
 * Key behaviors:
 * - Parent creates bookkeepers and moves to waiting room
 * - Bookkeepers create child bookkeepers and mark completion
 * - When all bookkeepers complete, entire group completes atomically
 * - All locks released together, all transactions completed together
 */
public class ACIDCompletionHandler implements CompletionHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(ACIDCompletionHandler.class);

    private final LockingService lockingService;
    private final BookkeeperService bookkeeperService;
    private final TransactionLifecyclePort lifecycle;
    private final TransactionGroupRegistry registry;

    public ACIDCompletionHandler(LockingService lockingService,
                                BookkeeperService bookkeeperService,
                                TransactionLifecyclePort lifecycle,
                                TransactionGroupRegistry registry) {
        this.lockingService = lockingService;
        this.bookkeeperService = bookkeeperService;
        this.lifecycle = lifecycle;
        this.registry = registry;
    }

    @Override
    public boolean completeParent(Transaction parent, BookkeeperOrchestrator orchestrator) {
        if (DebugConfig.printRuntimeDebugLogs()) {
            LOGGER.debug("completeParent called for {}", parent.getId());
        }
        // Validate COMPLETE phase
        if (parent.getPhase() != ExecutionPhase.COMPLETE) {
            throw new IllegalStateException(
                "Parent transaction " + parent.getId() + 
                " not at COMPLETE phase before bookkeeper creation. Current phase: " + 
                parent.getPhase());
        }
        
        TransactionGroup group = registry.get(parent);
        
        // Check if bookkeepers exist
        boolean hasBookkeepers = (group != null && !group.getBookkeepers().isEmpty());
        
        if (!hasBookkeepers) {
            // Create bookkeepers for parent (may be blocked by ON_RESTRICT)
            bookkeeperService.createAndQueueBookkeepers(parent);
            group = registry.get(parent);
            
            // If ON_RESTRICT marked the group, move to awaiting user action
            if (group != null && group.isMarkedForUserAction()) {
                return completeOnRestrictTransaction(parent);
            }
        }
        
        // Handle single transaction case (no bookkeepers created)
        if (group == null || group.getBookkeepers().isEmpty()) {
            return completeSingleTransaction(parent, group);
        }

        // ACID mode: Parent waits for all bookkeepers (atomic group completion)
        lifecycle.moveToWaitingRoom(parent);
        return false;
    }

    @Override
    public boolean completeBookkeeper(BookkeeperTransaction bookkeeper) {
        // Validate COMPLETE phase
        if (bookkeeper.getPhase() != ExecutionPhase.COMPLETE) {
            throw new IllegalStateException(
                "Bookkeeper transaction " + bookkeeper.getId() + 
                " not at COMPLETE phase before child bookkeeper creation. Current phase: " + 
                bookkeeper.getPhase());
        }
        
        TransactionGroup group = registry.get(bookkeeper);
        
        // ACID mode requires group to exist
        if (group == null) {
            throw new IllegalStateException(
                "No group found for bookkeeper " + bookkeeper.getId() + 
                " in registry. Bookkeeper cannot be completed without a group.");
        }

        // Create child bookkeepers for this bookkeeper (if not at max depth)
        bookkeeperService.createAndQueueBookkeepers(bookkeeper);
        
        group.markBookkeeperCompleted();
        
        // ACID mode: Wait for all bookkeepers, then complete group atomically
        if (group.isComplete()) {
            return completeGroupAtomically(group);
        } else {
            // Bookkeeper finished but group isn't complete yet
            lifecycle.moveToWaitingRoom(bookkeeper);
            return false;
        }
    }
    
    /**
     * Handle ON_RESTRICT transaction completion.
     * Releases locks and transitions to AWAITING_USER_ACTION state.
     */
    private boolean completeOnRestrictTransaction(Transaction parent) {
        lockingService.release(parent);
        parent.transitionTo(TransactionEvent.AWAITING_USER_ACTION);
        lifecycle.moveToAwaitingUserAction(parent);
        return true;
    }

    /**
     * Complete the entire group (parent + all bookkeepers) atomically.
     * All locks released together, all transactions completed together.
     */
    private boolean completeGroupAtomically(TransactionGroup group) {
        Transaction parent = group.getParent();

        // Parent should be at COMPLETE phase
        if (parent.getPhase() != ExecutionPhase.COMPLETE) {
            throw new IllegalStateException(
                "Group " + group.getGroupId() + ": Parent " + parent.getId() + 
                " not at COMPLETE phase. Current: " + parent.getPhase());
        }

        // Release all locks (parent + bookkeepers)
        recordGroupImpact(group);
        recordLockHoldOnRelease(group);
        lockingService.release(parent);
        for (var bk : group.getBookkeepers()) {
            lockingService.release(bk);
        }

        // Complete parent
        lifecycle.complete(parent);
        
        // Complete all bookkeepers - each should be at COMPLETE
        for (var bk : group.getBookkeepers()) {
            if (bk.getPhase() != ExecutionPhase.COMPLETE) {
                throw new IllegalStateException(
                    "Bookkeeper " + bk.getId() + " not at COMPLETE phase. " +
                    "Current: " + bk.getPhase());
            }
            lifecycle.complete(bk);
            lifecycle.getLogger().metrics().incrementBookkeepersCompleted();
        }
        
        lifecycle.getLogger().metrics().incrementGroupsCompleted();
        group.markCompleted();
        registry.clear(group);
        return true;
    }

    /**
     * lock_seconds_waiting_room: accumulate lockCountHeld * holdDuration for every member with
     * a tracked lock-hold interval (lockHoldStart >= 0), split by whether it passed through
     * the waiting room. holdDuration uses each member's own completionTime as its release
     * timestamp (reused, same convention as waitingRoomEntry) rather than threading a new
     * "current time" parameter through the completion path.
     */
    private void recordLockHoldOnRelease(TransactionGroup group) {
        Transaction parent = group.getParent();
        recordMemberLockHold(parent);
        for (var bk : group.getBookkeepers()) {
            recordMemberLockHold(bk);
        }
    }

    private void recordMemberLockHold(Transaction member) {
        if (member.getHeldBatches().isEmpty()) {
            return;
        }
        long release = member.getCompletionTime();
        boolean viaWaitingRoom = member.getWaitingRoomEntry() >= 0;
        // ONE record per batch, each measured from its OWN acquisition to the SINGLE release. Under
        // SS2PL every batch is held until commit, so batch 1's locks are held for the whole
        // transaction and batch k's for almost none - a descending sequence. Emitting per-batch
        // DURATIONS instead would measure execution time per batch, which collapses mean hold time
        // toward per-operation service time and would make the ladder's hold-time criterion pass
        // far too well for the wrong reason.
        for (Transaction.HeldBatch batch : member.getHeldBatches()) {
            lifecycle.getLogger().metrics().recordLockHold(
                    batch.lockCount(), Math.max(0L, release - batch.acquire()), viaWaitingRoom);
        }
        // The FIRST batch's hold IS the acquisition-to-release span - the "T" the hold-time
        // derivation is written against. Recorded separately because the obvious denominator,
        // submission-to-completion latency, also contains everything that happened BEFORE the first
        // lock was taken (admission waiting, blocked time, restarts), and that grows with N while
        // the hold does not. Normalising against it makes the criterion-3 ratio decline with load
        // for a reason that has nothing to do with lock holding.
        lifecycle.getLogger().metrics().recordAcquisitionSpan(
                Math.max(0L, release - member.getHeldBatches().get(0).acquire()));
        member.clearLockHoldTracking();
    }

    private void recordGroupImpact(TransactionGroup group) {
        Transaction parent = group.getParent();
        int parentResources = Math.max(1, parent.getResources().size());
        int cprResources = 0;
        long groupCompleteTime = Math.max(0L, parent.getCompletionTime());

        for (var bk : group.getBookkeepers()) {
            cprResources += bk.getResources().size();
            groupCompleteTime = Math.max(groupCompleteTime, bk.getCompletionTime());
        }

        // group-concurrency follow-up: parent.getLockHoldStart() is the
        // parent's C2PL preclaim admission time (set once, in
        // ACIDAdmissionController.tryAdmitWithLockCheck() - the only parent-admission call site,
        // per F1's finding) - read it here, BEFORE recordLockHoldOnRelease() clears it a few
        // lines below in the caller, so the group's full admission-to-completion span is
        // captured. Must run before that clear, not after.
        lifecycle.getLogger().metrics().recordGroupLifetime(parent.getLockHoldStart(), groupCompleteTime);
        // the Kaplan-Meier observation uses firstAdmissionTime, NOT
        // submissionTime - submissionTime is reset to "now" on every restart
        // (TransactionScheduler.restartTransaction(), kept that way for its own, different,
        // per-attempt-latency consumers), so it would silently measure only the FINAL attempt's
        // span instead of the whole lineage's age. Caught by an impossible result: a retry/backoff
        // decomposition comparing this "lifetime" against cumulative mandated backoff (itself
        // correctly cumulative, since lockRetryCount IS carried forward - see
        // TransactionFactory.restartTransaction()) produced deeply negative residuals at high
        // retry counts, only possible if the two were on different time bases. retryCount comes
        // straight from the transaction object, not a per-group accumulator - TransactionGroup
        // is NOT stable across restarts (TransactionFactory.restartTransaction() creates a new
        // one each time), so there is no cross-restart group state to read here.
        // Four-way lineage time decomposition: close out whatever phase is still open (almost
        // always RUNNING, since completion only happens from an admitted state) before reading
        // the four accumulators - see Transaction.finalizeLineageTimeAccounting()'s javadoc.
        parent.finalizeLineageTimeAccounting(groupCompleteTime);
        lifecycle.getLogger().metrics().recordGroupLifetimeObservation(
                parent.getFirstAdmissionTime(), groupCompleteTime, parent.getLockRetryCount(),
                group.getMaxBookkeeperDepth(), parent.getCumulativePendingQueue(),
                parent.getCumulativeBackoff(), parent.getCumulativeAdmissionWait(),
                parent.getCumulativeRunning(), parent.getCumulativeWaitingRoom(),
                parent.getCumulativeRunningWasted());

        long syncDelay = Math.max(0L, groupCompleteTime - parent.getCompletionTime());
        double amplification = (parent.getResources().size() + cprResources) / (double) parentResources;
        lifecycle.getLogger().metrics().recordCprSynchronizationDelay(syncDelay);
        lifecycle.getLogger().metrics().recordCascadeAmplification(amplification);
        lifecycle.getLogger().metrics().recordCascadeResourceReferences(parent.getResources().size(), cprResources);
        lifecycle.getLogger().recordCompletedGroupResourceReferences(parent.getResources().size(), cprResources);
        
        // Push cascade structure metrics to global snapshot at commit time (avoids abort inflation)
        lifecycle.getLogger().metrics().incrementAttemptedBookkeepers(group.getAttemptedBookkeepers());
        lifecycle.getLogger().metrics().incrementBookkeepersCreated(group.getBookkeepers().size());
        int[] depthCounts = group.getBookkeepersAtDepth();
        for (int i = 0; i < depthCounts.length; i++) {
            for (int c = 0; c < depthCounts[i]; c++) {
                lifecycle.getLogger().metrics().recordBookkeeperDepth(i);
            }
        }
        }

    /**
     * Handle completion of a single transaction (no bookkeepers).
     */
    private boolean completeSingleTransaction(Transaction txn, TransactionGroup group) {
        // Defensive check for ON_RESTRICT (should have been handled earlier)
        if (txn.getState() == TransactionState.AWAITING_USER_ACTION) {
            return false;
        }

        // Transaction should be at COMPLETE phase
        if (txn.getPhase() != ExecutionPhase.COMPLETE) {
            throw new IllegalStateException(
                "Transaction " + txn.getId() + " not at COMPLETE phase. " +
                "Current: " + txn.getPhase());
        }

        lockingService.release(txn);
        lifecycle.complete(txn);

        if (group != null) {
            // single-transaction "group" lifetime is just txn's own admission-
            // to-completion span. Scoped inside this null check, matching every other
            // .metrics() call in this branch - some tests exercise the group==null path with a
            // logger stub that doesn't expect metrics() to be touched at all.
            lifecycle.getLogger().metrics().recordGroupLifetime(txn.getLockHoldStart(), txn.getCompletionTime());
            // See the recordGroupImpact() call site for why this uses firstAdmissionTime/
            // getLockRetryCount() rather than submissionTime/lockHoldStart/a per-group
            // accumulator.
            txn.finalizeLineageTimeAccounting(txn.getCompletionTime());
            lifecycle.getLogger().metrics().recordGroupLifetimeObservation(
                    txn.getFirstAdmissionTime(), txn.getCompletionTime(), txn.getLockRetryCount(),
                    group.getMaxBookkeeperDepth(), txn.getCumulativePendingQueue(),
                    txn.getCumulativeBackoff(), txn.getCumulativeAdmissionWait(),
                    txn.getCumulativeRunning(), txn.getCumulativeWaitingRoom(),
                    txn.getCumulativeRunningWasted());
            lifecycle.getLogger().recordCompletedGroupResourceReferences(txn.getResources().size(), 0);
            // Zero-bookkeeper completions were recording NO lock hold at all: recordLockHoldOnRelease
            // was called only from completeGroupAtomically() (bookkeepers present) and the abort
            // path, so a group that completes as a single transaction contributed nothing to
            // getMeanLockHoldDurationPerLock. That is the ENTIRE static arm (semanticOverlap=0 ->
            // no CPRs -> every group is single-member), which is precisely the arm the
            // operation-level ladder's hold-time criterion gates on - the metric read a flat zero
            // there. Found by LockCountAccountingTest, which asserts completions x k against the
            // recorded total.
            //
            // Must come AFTER recordGroupLifetime above: recordMemberLockHold() clears
            // lockHoldStart, and that call reads it. Same ordering constraint the group path
            // documents at its recordGroupImpact()/recordLockHoldOnRelease() pair.
            recordMemberLockHold(txn);
            lifecycle.getLogger().metrics().incrementGroupsCompleted();
            group.markCompleted();
            registry.clear(group);
        }
        
        return true;
    }
}
