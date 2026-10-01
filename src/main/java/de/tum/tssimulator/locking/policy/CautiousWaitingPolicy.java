package de.tum.tssimulator.locking.policy;

import java.util.Set;

import de.tum.tssimulator.core.Transaction;
import de.tum.tssimulator.core.TransactionGroup;
import de.tum.tssimulator.core.TransactionState;
import de.tum.tssimulator.locking.core.LockWaitTracker;
import de.tum.tssimulator.queue.QueueType;
import de.tum.tssimulator.scheduling.TransactionScheduler;
import de.tum.tssimulator.state.TransactionGroupRegistry;

public class CautiousWaitingPolicy implements WaitPolicyStrategy {
    private final QueueType blockQueue;
    private final TransactionGroupRegistry registry;
    private final LockWaitTracker waitTracker;
    private final TransactionScheduler scheduler;

    // Diagnostic-only, reviewer question (dynamic-footprint-variance-investigation memory,
    // S2PL-oracle "zero aborts" mechanism check): distinguishes "evaluate() ran but the
    // hard-deny condition never held" (many evaluations, zero fires - structural: the
    // holders-blocked condition is never satisfied) from "evaluate() barely ran at all" (few/zero
    // evaluations - a different, less interesting explanation: this arm rarely even conflicts
    // with an already-blocked holder in the first place). Cheap unconditional counters (two
    // long increments per evaluate() call, negligible next to the rest of this method) rather
    // than a flag-gated facility, since there's no expensive per-call work to skip - see
    // getEvaluationsTotal()/getHardDenyFiredTotal()/resetDiagnosticCounters().
    private static long evaluationsTotal = 0;
    private static long hardDenyFiredTotal = 0;

    /**
     * Reset the process-global cautious-waiting counters, so a run's hard_deny_rate does not
     * depend on how many runs preceded it in this JVM.
     *
     * <p>THESE ARE STATICS THAT FEED AN OUTPUT COLUMN. {@code hard_deny_rate} is
     * {@code hardDenyFiredTotal / evaluationsTotal}, and both accumulate for the life of the JVM.
     * Under the campaign's one-run-per-JVM discipline that is correct; under any driver that
     * loops cells in-process it silently reports the run-so-far, a column that looks plausible
     * and drifts with position in the run. A process-global counter with no per-run reset has
     * already cost this project one retracted measurement, so the reset lives in
     * {@code Simulator}'s constructor rather than in a comment telling the next author not to
     * write such a driver.
     */
    public static void resetCounters() {
        evaluationsTotal = 0;
        hardDenyFiredTotal = 0;
    }

    public static long getEvaluationsTotal() { return evaluationsTotal; }
    public static long getHardDenyFiredTotal() { return hardDenyFiredTotal; }
    public static void resetDiagnosticCounters() { evaluationsTotal = 0; hardDenyFiredTotal = 0; }

    public CautiousWaitingPolicy(QueueType blockQueue,
                                 TransactionGroupRegistry registry,
                                 LockWaitTracker waitTracker,
                                 TransactionScheduler scheduler) {
        this.blockQueue = blockQueue;
        this.registry = registry;
        this.waitTracker = waitTracker;
        this.scheduler = scheduler;
    }

    @Override
    public LockDecision evaluate(Transaction requester, Set<Transaction> holders) {
        // Standard cautious waiting: if ANY holder's group is blocked,
        // abort the REQUESTER to prevent cycles
        evaluationsTotal++;
        // The requester's OWN group is excluded from the holder scan. Before per-operation modes a
        // requester could never be among the holders of a lock it was being denied - it either held
        // the lock (granted outright) or did not. An upgrade breaks that: a transaction holding
        // SHARED alongside co-readers and requesting EXCLUSIVE is denied while appearing in its own
        // holder set. isBlocked() counts READY and BLOCKED as blocked, so such a requester would
        // match itself and hard-deny - aborting its own group - every single time, and the abort
        // would be attributed to cautious waiting rather than to the co-readers that actually
        // prevented the upgrade.
        boolean anyBlockerGroupIsBlocked = holders.stream()
                .filter(holder -> !isSameGroup(holder, requester))
                .anyMatch(this::isTransactionGroupBlocked);
        if (anyBlockerGroupIsBlocked) {
            hardDenyFiredTotal++;
        }

        return anyBlockerGroupIsBlocked
                ? new HardDenyDecision(scheduler)
                : new SoftDenyDecision(blockQueue);
    }

    /** Same transaction, or two members of one group - which shares its locks, so neither can be
     * the thing blocking the other. */
    private boolean isSameGroup(Transaction holder, Transaction requester) {
        if (holder == requester) {
            return true;
        }
        return holder.getGroup() != null && holder.getGroup() == requester.getGroup();
    }

    private boolean isTransactionGroupBlocked(Transaction t) {
        // Check if ANY member of this transaction's group is in BLOCKED state
        // This includes the parent and all bookkeepers
        
        if (registry == null) {
            // Fallback to old behavior if registry not set
            return isBlocked(t);
        }
        
        TransactionGroup group = registry.get(t);
        if (group == null) {
            // No group - check just this transaction
            return isBlocked(t);
        }
        
        // Check if parent is blocked
        Transaction parent = group.getParent();
        if (parent != null && isBlocked(parent)) {
            return true;
        }
        
        // Check if any bookkeeper is blocked
        for (var bk : group.getBookkeepers()) {
            if (isBlocked(bk)) {
                return true;
            }
        }
        
        return false;
    }

    private boolean isBlocked(Transaction t) {
        // Check if the transaction is NOT actively making progress
        // This includes:
        // 1. BLOCKED state - waiting for locks (true blocking)
        // 2. READY state - waiting for execution slot (pseudo-blocking)
        
        // Primary check: Is transaction registered as waiting for locks?
        if (waitTracker.isWaiting(t)) {
            return true;
        }
        
        // Fallback: Check FSM state
        // BLOCKED = waiting for locks (cannot proceed)
        // READY = waiting for execution slot (cannot proceed)
        // 
        // CRITICAL: READY transactions MUST be treated as "blocked" for cautious waiting!
        // Even though they have all their locks, they're not actively executing.
        // If another transaction waits for their locks, we get pseudo-deadlock:
        //   - Holder (READY) waits for execution capacity
        //   - Waiter (RUNNING) waits for holder's locks
        //   - Neither makes progress
        // Solution: Treat READY as blocked → abort the waiter (cautious waiting)
        
        TransactionState state = t.getState();
        return state == TransactionState.BLOCKED
            || state == TransactionState.READY;
    }
}
