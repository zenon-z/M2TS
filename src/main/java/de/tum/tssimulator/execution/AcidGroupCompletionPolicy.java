package de.tum.tssimulator.execution;

import de.tum.tssimulator.bookkeeping.BookkeeperTransaction;
import de.tum.tssimulator.core.ExecutionPhase;
import de.tum.tssimulator.core.Transaction;
import de.tum.tssimulator.core.TransactionGroup;
import de.tum.tssimulator.lifecycle.TransactionLifecyclePort;
import de.tum.tssimulator.monitoring.SimulationLogger;
import de.tum.tssimulator.state.TransactionGroupRegistry;

class AcidGroupCompletionPolicy extends AbstractGroupCompletionPolicy {
    AcidGroupCompletionPolicy(
            TransactionProcessor processor,
            TransactionLifecyclePort lifecycle,
            TransactionGroupRegistry registry) {
        super(processor, lifecycle, registry);
    }

    @Override
    public boolean completeBookkeeper(BookkeeperTransaction bookkeeper) {
        assertAtCompletePhase(bookkeeper, "Bookkeeper transaction");

        TransactionGroup group = registry.get(bookkeeper);
        if (group == null) {
            throw new IllegalStateException(
                    "No group found for bookkeeper " + bookkeeper.getId()
                            + " in registry. Bookkeeper cannot be completed without a group.");
        }

        processor.createAndQueueBookkeepers(bookkeeper);
        group.markBookkeeperCompleted();

        if (group.isComplete()) {
            return completeGroupAtomically(group);
        }

        lifecycle.moveToWaitingRoom(bookkeeper);
        return false;
    }

    @Override
    public boolean completeTransaction(Transaction parent, BookkeeperOrchestrator orchestrator) {
        assertAtCompletePhase(parent, "Parent transaction");
        assert parent.getPhase() == ExecutionPhase.COMPLETE
                : "Assertion failed: Parent " + parent.getId() + " not at COMPLETE phase";

        TransactionGroup group = registry.get(parent);
        if (group != null && group.isMarkedForUserAction()) {
            return completeOnRestrictTransaction(parent);
        }

        boolean hasBookkeepers = (group != null && !group.getBookkeepers().isEmpty());
        if (!hasBookkeepers) {
            processor.createAndQueueBookkeepers(parent);
            group = registry.get(parent);

            if (group != null && group.isMarkedForUserAction()) {
                return completeOnRestrictTransaction(parent);
            }
        }

        if (group == null || group.getBookkeepers().isEmpty()) {
            return completeSingleTransaction(parent, group);
        }

        lifecycle.moveToWaitingRoom(parent);
        return false;
    }

    private boolean completeGroupAtomically(TransactionGroup group) {
        Transaction parent = group.getParent();
        if (parent.getPhase() != ExecutionPhase.COMPLETE) {
            throw new IllegalStateException(
                    "Group " + group.getGroupId() + ": Parent " + parent.getId()
                            + " not at COMPLETE phase in completeGroupAtomically(). Current phase: "
                            + parent.getPhase() + ". This should be impossible due to phase enforcement.");
        }

        SimulationLogger logger = lifecycle.getLogger();
        recordGroupImpact(group);
        recordLockHoldOnRelease(group);
        processor.release(parent);
        for (var bk : group.getBookkeepers()) {
            processor.release(bk);
        }

        lifecycle.complete(parent);
        for (var bk : group.getBookkeepers()) {
            if (bk.getPhase() != ExecutionPhase.COMPLETE) {
                throw new IllegalStateException(
                        "Bookkeeper " + bk.getId() + " not at COMPLETE phase. Current: " + bk.getPhase());
            }
            lifecycle.complete(bk);
            if (logger != null) {
                logger.metrics().incrementBookkeepersCompleted();
            }
        }

        if (logger != null) {
            logger.metrics().incrementGroupsCompleted();
        }
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
        if (member.getLockHoldStart() < 0) {
            return;
        }
        long holdDuration = Math.max(0L, member.getCompletionTime() - member.getLockHoldStart());
        lifecycle.getLogger().metrics().recordLockHold(
                member.getLockCountHeld(), holdDuration, member.getWaitingRoomEntry() >= 0);
        member.clearLockHoldTracking();
    }

    private void recordGroupImpact(TransactionGroup group) {
        SimulationLogger logger = lifecycle.getLogger();
        if (logger == null) {
            return;
        }

        Transaction parent = group.getParent();
        int parentResources = Math.max(1, parent.getResources().size());
        int cprResources = 0;
        long groupCompleteTime = Math.max(0L, parent.getCompletionTime());

        for (var bk : group.getBookkeepers()) {
            cprResources += bk.getResources().size();
            groupCompleteTime = Math.max(groupCompleteTime, bk.getCompletionTime());
        }

        logger.metrics().recordCprSynchronizationDelay(
                Math.max(0L, groupCompleteTime - parent.getCompletionTime()));
        logger.metrics().recordCascadeAmplification(
                (parent.getResources().size() + cprResources) / (double) parentResources);
        logger.metrics().recordCascadeResourceReferences(parent.getResources().size(), cprResources);
        logger.recordCompletedGroupResourceReferences(parent.getResources().size(), cprResources);
    }
}
