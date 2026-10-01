package de.tum.tssimulator.execution;

import de.tum.tssimulator.bookkeeping.BookkeeperTransaction;
import de.tum.tssimulator.core.ExecutionPhase;
import de.tum.tssimulator.core.Transaction;
import de.tum.tssimulator.core.TransactionGroup;
import de.tum.tssimulator.lifecycle.TransactionLifecyclePort;
import de.tum.tssimulator.monitoring.LogEvent;
import de.tum.tssimulator.state.DirtyKey;
import de.tum.tssimulator.state.DirtyKeyUtil;
import de.tum.tssimulator.state.DirtyStateTracker;
import de.tum.tssimulator.state.TransactionGroupRegistry;

/**
 * BASE completion handler - eventual consistency.
 * 
 * Parent completes immediately without waiting for bookkeepers. Bookkeepers
 * run asynchronously in the background. This provides better performance at
 * the cost of eventual consistency instead of strong consistency.
 * 
 * Key behaviors:
 * - Parent creates bookkeepers and completes immediately
 * - Each bookkeeper completes independently (no groups)
 * - No waiting, no atomic group completion
 * - Locks released as soon as transaction finishes (not held for group)
 */
public class BASECompletionHandler implements CompletionHandler {
    private final LockingService lockingService;
    private final BookkeeperService bookkeeperService;
    private final TransactionLifecyclePort lifecycle;
    private final DirtyStateTracker dirtySetManager;
    private final TransactionGroupRegistry registry;

    public BASECompletionHandler(LockingService lockingService,
                                BookkeeperService bookkeeperService,
                                TransactionLifecyclePort lifecycle,
                                DirtyStateTracker dirtySetManager,
                                TransactionGroupRegistry registry) {
        this.lockingService = lockingService;
        this.bookkeeperService = bookkeeperService;
        this.lifecycle = lifecycle;
        this.dirtySetManager = dirtySetManager;
        this.registry = registry;
    }

    public BASECompletionHandler(LockingService lockingService,
                                BookkeeperService bookkeeperService,
                                TransactionLifecyclePort lifecycle,
                                DirtyStateTracker dirtySetManager) {
        this(lockingService, bookkeeperService, lifecycle, dirtySetManager, null);
    }

    @Override
    public boolean completeParent(Transaction parent, BookkeeperOrchestrator orchestrator) {
        // Validate COMPLETE phase
        if (parent.getPhase() != ExecutionPhase.COMPLETE) {
            throw new IllegalStateException(
                "Parent transaction " + parent.getId() + 
                " not at COMPLETE phase. Current phase: " + parent.getPhase());
        }
        
        // BASE mode: Create bookkeepers but don't wait for them
        bookkeeperService.createAndQueueBookkeepers(parent);

        TransactionGroup group = registry != null ? registry.get(parent) : null;
        
        // Release locks and complete immediately (eventual consistency)
        lockingService.release(parent);
        lifecycle.complete(parent);
        // GROUP COMPLETION IS NOT A PARENT EVENT, so neither groupsCompleted nor
        // registry.clear(group) belongs here at parent commit. Doing it here breaks two things:
        //
        //   1. clearing the group at commit makes every later cascade CU's registry lookup MISS, so
        //      getOrCreateGroup re-registers the root parent - 1170 times against 100 real
        //      parents - inflating groupsStarted and suppressing pool replacement;
        //   2. a parent whose group has already been cleared commits with group == null, so its
        //      completion went uncounted - 100 groups wedged open with every queue empty and every
        //      state count zero.
        //
        // Parent commit stays immediate - that IS BASE - but it is a TRANSACTION event, not a
        // group event. The group now stays registered until its last bookkeeper drains, which is
        // also what F2/F3 need: dirty_post_recon_* and recovery latency require the group's
        // bookkeepers tracked to the end.
        //
        // The one case that must still complete here: a group with NO bookkeepers has nothing to
        // wait for and would otherwise never complete at all.
        // The parent has left the PRODUCER population either way - that is what the pool manager
        // holds at the configured MPL, and it must not wait for drains.
        lifecycle.getLogger().metrics().incrementParentCommitted();

        if (group != null && group.getBookkeepers().isEmpty()) {
            // No bookkeepers to wait for, so the GROUP is done at the same moment. incrementGroups
            // CompletedWithoutParent, because the parent was already counted just above.
            lifecycle.getLogger().metrics().incrementGroupsCompletedWithoutParent();
            group.markCompleted();
            registry.clear(group);
        }
        
        return true;  // Parent is done - bookkeepers continue in background
    }

    @Override
    public boolean completeBookkeeper(BookkeeperTransaction bookkeeper) {
        // Validate COMPLETE phase
        if (bookkeeper.getPhase() != ExecutionPhase.COMPLETE) {
            throw new IllegalStateException(
                "Bookkeeper transaction " + bookkeeper.getId() + 
                " not at COMPLETE phase. Current phase: " + bookkeeper.getPhase());
        }
        
        // SINGLE clearing point for both inline and dedicated reconciler paths
        if (dirtySetManager != null && bookkeeper.usesBackgroundWorkLifecycle()) {
            DirtyKey key = DirtyKeyUtil.fromBookkeeper(bookkeeper);
            
            if (key == null) {
                // Track invariant violation: BASE bookkeeper without valid dirty key
                lifecycle.getLogger().metrics().incrementMissingDirtyKey();
                lifecycle.getLogger().logEvent(new LogEvent(
                    LogEvent.Type.WARNING,
                    bookkeeper.getId(),
                    0L,
                    "BASE bookkeeper missing valid DirtyKey (no targetModel or resources)"));
            } else {
                long completionTime = bookkeeper.getCompletionTime();
                dirtySetManager.clearDirty(key, completionTime);
                
                lifecycle.getLogger().logEvent(new LogEvent(
                    LogEvent.Type.DIRTY_CLEARED,
                    bookkeeper.getId(),
                    completionTime,
                    "clearedDirty=" + key + " (BASE bookkeeper, consistency restored)"));
            }
        }
        
        // Record dirty set size for ALL bookkeepers in BASE mode (not just BaseBookkeeperTransaction)
        // This matches reconciliationBookkeepers counter which counts all types
        if (dirtySetManager != null) {
            lifecycle.getLogger().recordDirtySetSize(dirtySetManager.size());
        }
        
        // BASE mode: No groups, each bookkeeper completes independently
        
        // Create child bookkeepers for this bookkeeper (if not at max depth)
        bookkeeperService.createAndQueueBookkeepers(bookkeeper);
        
        // Release and complete this bookkeeper independently
        lockingService.release(bookkeeper);
        
        // Double-check phase (should still be COMPLETE after release)
        if (bookkeeper.getPhase() != ExecutionPhase.COMPLETE) {
            throw new IllegalStateException(
                "Bookkeeper " + bookkeeper.getId() + " not at COMPLETE phase. " +
                "Current phase: " + bookkeeper.getPhase());
        }
        
        lifecycle.complete(bookkeeper);
        lifecycle.getLogger().metrics().incrementBookkeepersCompleted();

        // The group completes HERE, when its last bookkeeper drains - not at parent commit.
        // markBookkeeperCompleted() advances completedCount and flips the group's own state once
        // completedCount == bookkeepers.size().
        //
        // KNOWN LIMIT, stated rather than discovered later: bookkeepers are appended as deeper
        // cascade levels are created, so at fan-out depth > 1 a group can read complete between
        // levels - the first level drains before the second is added. V3's design point is depth 1
        // (LINE, 2 models), where every CU exists before any runs, so this is exact there. A
        // deeper-cascade BASE experiment needs the group to know its cascade is finished, which is
        // a different mechanism than counting.
        TransactionGroup bkGroup = registry != null ? registry.get(bookkeeper) : null;
        if (bkGroup != null) {
            bkGroup.markBookkeeperCompleted();
            if (bkGroup.isComplete()) {
                // Group only - this parent committed long ago and was counted then.
                lifecycle.getLogger().metrics().incrementGroupsCompletedWithoutParent();
                registry.clear(bkGroup);
            }
        }
        
        return true;  // This bookkeeper is done
    }
}
