package de.tum.tssimulator.bootstrap;

import java.util.ArrayList;
import java.util.List;

import de.tum.tssimulator.bookkeeping.BookkeeperTransaction;
import de.tum.tssimulator.core.Transaction;
import de.tum.tssimulator.core.TransactionGroup;
import de.tum.tssimulator.core.TransactionGroupState;
import de.tum.tssimulator.core.TransactionState;
import de.tum.tssimulator.queue.CentralQueueManager;
import de.tum.tssimulator.queue.QueuePort;

/**
 * Invariant check: every RUNNING (or BACKGROUND_WORK) transaction must be reachable from
 * somewhere - either sitting in one of CentralQueueManager's queues, or `current` in some
 * processor unit. If it's in neither, nothing in the simulation will ever advance or terminate
 * it again: a dropped reference.
 *
 * Written after a multi-session investigation (see the dynamic-footprint-variance-investigation
 * project memory / VictimTraceLog) that eventually found exactly this failure mode by hand, via a
 * targeted replay trace, one victim ID at a time. The root cause (TransactionGroup.
 * clearFromProcessors() being re-entrant on an already-ABORTING group - see its javadoc) is
 * fixed, but this class exists so the NEXT dropped-reference bug - and there is no reason to
 * believe this is the only possible one, in a simulator this size - gets caught by an assertion
 * in minutes instead of a week of hypothesis-and-refutation.
 *
 * Deliberately NOT run every simulation step (each call is an O(active groups x processors) scan
 * of every processor unit per member) - wired at the same two points DeadlockStateDumper already
 * fires (watchdog trip, normal run end), which are rare enough that the cost is negligible.
 */
public final class OrphanTransactionAudit {

    private OrphanTransactionAudit() {
    }

    /** One orphaned transaction: who it is, and where it was last known to be. */
    public record Orphan(String txnId, String role, TransactionState state, String phase, String groupParentId) {
    }

    /**
     * Scans every active group's members. Returns the list of orphans found (empty if the
     * invariant holds). Non-throwing by default so it's safe to call unconditionally in
     * production diagnostics; callers that want a hard failure (tests) should assert the
     * returned list is empty.
     */
    public static List<Orphan> audit(SimulatorContext ctx) {
        List<Orphan> orphans = new ArrayList<>();
        QueuePort queues = ctx.scheduling().queues();
        if (!(queues instanceof CentralQueueManager cqm)) {
            return orphans;  // Only CentralQueueManager exposes the processor-assignment check.
        }

        for (TransactionGroup group : ctx.registry().getActiveGroups()) {
            Transaction parent = group.getParent();
            if (parent == null) {
                continue;
            }
            // The parent should ALWAYS be findable, even mid-abort: TransactionGroupAbortCoordinator.
            // abortGroup() removes it from queues and re-enqueues it to ROLLBACK back-to-back,
            // with no step in between where it's legitimately unowned.
            checkMember(cqm, parent, "parent", parent.getId(), orphans);

            // Bookkeepers are a different story: abortGroup()'s Step 1 (removeFromQueues())
            // removes EVERY member from all queues immediately, but only the PARENT gets
            // re-enqueued (to ROLLBACK) - bookkeepers are deliberately left unowned until
            // completeRollback() (triggered once the parent's rollback phase finishes) calls
            // transitionMembersToAborted() and settles them. That's a real, by-design, BOUNDED
            // window (bounded by the parent's rollback duration - see IOModel.getRollbackTime()),
            // not a dropped reference, so don't flag bookkeepers while their group is still
            // ABORTING. If completeRollback() itself never fires (the bug this audit exists to
            // catch), the PARENT check above still catches it directly.
            if (group.getState() == TransactionGroupState.ABORTING) {
                continue;
            }
            for (BookkeeperTransaction bk : group.getBookkeepers()) {
                checkMember(cqm, bk, "bookkeeper", parent.getId(), orphans);
            }
        }
        return orphans;
    }

    private static void checkMember(CentralQueueManager cqm, Transaction t, String role,
            String groupParentId, List<Orphan> orphans) {
        TransactionState state = t.getState();
        if (state != TransactionState.RUNNING && state != TransactionState.BACKGROUND_WORK) {
            return;  // Only RUNNING/BACKGROUND_WORK members are expected to be owned by
                     // something right now - every other state has its own legitimate home
                     // (a queue, the backoff queue, or a terminal state with no home needed).
        }
        if (cqm.contains(t) || cqm.isAssignedToAnyProcessor(t)) {
            return;
        }
        orphans.add(new Orphan(t.getId(), role, state, t.getPhase().name(), groupParentId));
    }

    /** Convenience: format the audit result as one diagnostic line, empty string if clean. */
    public static String describe(List<Orphan> orphans) {
        if (orphans.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("ORPHAN_TRANSACTIONS count=").append(orphans.size());
        for (Orphan o : orphans) {
            sb.append(" [id=").append(o.txnId())
              .append(" role=").append(o.role())
              .append(" state=").append(o.state())
              .append(" phase=").append(o.phase())
              .append(" group=").append(o.groupParentId())
              .append(']');
        }
        return sb.toString();
    }
}
