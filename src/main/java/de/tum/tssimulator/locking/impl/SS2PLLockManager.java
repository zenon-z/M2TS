package de.tum.tssimulator.locking.impl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import de.tum.tssimulator.bookkeeping.BookkeeperTransaction;
import de.tum.tssimulator.core.EngineEventTrace;
import de.tum.tssimulator.core.Transaction;
import de.tum.tssimulator.core.TransactionGroup;
import de.tum.tssimulator.core.TransactionState;
import de.tum.tssimulator.execution.AdaptiveConcurrencyManager;
import de.tum.tssimulator.execution.AdmissionWaitManager;
import de.tum.tssimulator.execution.MplAdaptationManager;
import de.tum.tssimulator.lifecycle.BlockedTransactionRetrier;
import de.tum.tssimulator.locking.core.AdmissionAwareLockManager;
import de.tum.tssimulator.locking.core.LockManager;
import de.tum.tssimulator.locking.core.LockStateView;
import de.tum.tssimulator.locking.core.LockType;
import de.tum.tssimulator.locking.core.LockWaitTracker;
import de.tum.tssimulator.locking.policy.AdaptiveWaitPolicy;
import de.tum.tssimulator.locking.policy.GrantedDecision;
import de.tum.tssimulator.locking.policy.HardDenyDecision;
import de.tum.tssimulator.locking.policy.LockDecision;
import de.tum.tssimulator.locking.policy.WaitPolicyStrategy;
import de.tum.tssimulator.monitoring.LogEvent;
import de.tum.tssimulator.monitoring.SimulationLogger;
import de.tum.tssimulator.queue.QueueManager;
import de.tum.tssimulator.scheduling.TransactionScheduler;
import de.tum.tssimulator.state.TransactionGroupRegistry;

public class SS2PLLockManager implements LockManager, AdmissionAwareLockManager, LockStateView {
    private final Map<String, LockInfo> lockTable = new HashMap<>();
    /** SHARED -> EXCLUSIVE mode changes granted, and requests denied because co-readers made an
     * upgrade impossible. Separated because they are different phenomena: the first is a repeat
     * touch escalating, the second is contention that only per-operation modes can produce. */
    private long upgradesGranted = 0L;
    private long upgradesDenied = 0L;
    // Reverse index (transaction -> the lock IDs it currently holds), so
    // releaseLocks()/getWaitersForTransaction()/holdsAnyLocks() only touch the handful of locks a
    // transaction actually holds instead of scanning the ENTIRE lockTable. Without it,
    // releaseLocks(t) iterates every currently-held lock system-wide on every completion or
    // abort, regardless of how many locks t itself holds - O(all held locks) per release. Cheap at
    // low contention (small lockTable), but catastrophic once demand exceeds totalLocks and
    // releases fire in bursts (measured via jstack: two thread dumps 40s apart both
    // pegged at ~100% CPU cycling through this exact path at S2PL/static/k=10/mpl=512, where
    // mpl*k=5120 first exceeds totalLocks=5000 - a multi-hour stall on a single sweep point that
    // resolved in seconds once profiled to this scan). Maintained at the only 3 call sites in
    // this class that ever add a holder (requestLock()'s new-lock and SHARED-read-read grants,
    // tryAcquireAllLocks()'s new-lock grant) and the only 1 that ever removes one (releaseLocks()
    // itself) - verified via a full-repo grep for LockInfo.holders mutations.
    private final Map<Transaction, Set<String>> locksHeldBy = new HashMap<>();
    private final WaitPolicyStrategy waitPolicy;
    /** Optional - null in unit tests that construct this directly. */
    private SimulationLogger metricsLogger;
    private final LockWaitTracker waitTracker;
    private BlockedTransactionRetrier lifecycleManager;
    private AdmissionWaitManager admissionWaitManager;
    private TransactionGroupRegistry registry;
    private QueueManager queueManager;

    public SS2PLLockManager(WaitPolicyStrategy waitPolicy, LockWaitTracker waitTracker) {
        this.waitPolicy = waitPolicy;
        this.waitTracker = waitTracker;
    }

    public void setRegistry(TransactionGroupRegistry registry) {
        this.registry = registry;
    }
    
    @Override
    public void setAdmissionWaitManager(AdmissionWaitManager manager) {
        this.admissionWaitManager = manager;
    }
    
    public void setLifecycleManager(BlockedTransactionRetrier manager) {
        this.lifecycleManager = manager;
    }

    /**
     * Optional metrics sink for the conflict-rate counter. Setter-injected like every other
     * collaborator here because this class is constructed before the logger is available - see
     * the "no logger to pass" note on the deadlock path.
     */
    public void setMetricsLogger(SimulationLogger logger) {
        this.metricsLogger = logger;
    }

    public void setQueueManager(QueueManager queueManager) {
        this.queueManager = queueManager;
    }

    @Override
    public void registerWaiter(Transaction txn, String lockId) {
        waitTracker.markWaiting(txn, lockId);
    }

    public boolean isWaitingForLock(Transaction txn) {
        return waitTracker.isWaiting(txn);
    }

    @Override
    public Set<Transaction> getWaitersForTransaction(Transaction holder) {
        // Get all transactions waiting for locks currently held by this transaction
        Set<Transaction> allWaiters = new LinkedHashSet<>();

        if (waitTracker instanceof MapBackedLockWaitTracker) {
            MapBackedLockWaitTracker tracker = (MapBackedLockWaitTracker) waitTracker;
            // Only the locks holder itself holds (via the reverse index), not a scan of every
            // lock in the system - see locksHeldBy above. This call site fires on every
            // cautious-waiting cascade (abortWaitersBlockedByGroup()), which is exactly the
            // contention burst where a full-table scan is most expensive.
            Set<String> heldByHolder = locksHeldBy.get(holder);
            if (heldByHolder != null) {
                for (String lockId : heldByHolder) {
                    allWaiters.addAll(tracker.getWaitersForLock(lockId));
                }
            }
        }

        return allWaiters;
    }

    private void removeFromWaiters(Transaction txn, String lockId) {
        waitTracker.clearWaiting(txn, lockId);
    }


    /**
     * Can {@code t} have {@code res} in {@code requested} mode right now, without waiting?
     *
     * <p>The single compatibility rule, shared by {@link #requestLock},
     * {@link #checkLocksAvailable} and {@link #tryAcquireAllLocks}. A {@code holders.isEmpty()}
     * test in place of this one is mode-blind, and makes a C2PL preclaim treat a SHARED lock as a
     * conflict for a SHARED requester. Compatibility is a property of the lock manager rather than
     * of the acquisition protocol, so all three callers ask this one question and cannot
     * disagree.
     *
     * <p>Pure: no mutation, no wait-policy invocation. Callers that grant do their own bookkeeping;
     * callers that cannot grant decide for themselves whether to wait, deny, or report the blocker.
     */
    private boolean canGrant(Transaction t, LockInfo current, LockType requested) {
        if (current == null) {
            return true; // no entry at all - free
        }
        // AN ENTRY WITH AN EMPTY HOLDER SET IS DELIBERATELY NOT TREATED AS FREE HERE. Falling
        // through reaches waitPolicy.evaluate(t, emptyHolders), which anyMatch()es to false and
        // yields a SOFT DENY, so a stale empty entry blocks the requester rather than granting
        // it. That reads like a defect - nobody holds the lock - and it is not cosmetic: treating
        // such an entry as free moves S2PL by -3%..+4% across the ladder, while C2PL, which does
        // not route through requestLock, stays identical. Changing it is therefore a behavioural
        // change needing its own baseline, not a cleanup.
        //
        // Self and group checks cannot match an empty holder set, so the outcome is the S/S test
        // below and otherwise a deny.
        if (current.holders.contains(t)) {
            // Already a holder. Sufficient unless this is an upgrade, and an upgrade is only
            // possible when nobody else is reading: with co-readers present, EXCLUSIVE cannot be
            // granted and the request must wait like any other conflict.
            //
            // Returning GrantedDecision for ANY self-held lock is wrong: a transaction holding
            // SHARED alongside other readers and requesting EXCLUSIVE would be granted without
            // the mode ever changing, and would then believe it held an exclusive lock that other
            // readers were still sharing.
            boolean isUpgrade = current.type == LockType.SHARED && requested == LockType.EXCLUSIVE;
            return !isUpgrade || current.holders.size() == 1;
        }
        if (groupHolds(t, current)) {
            return true; // parent/sibling already holds it - groups share their locks
        }
        return current.type == LockType.SHARED && requested == LockType.SHARED;
    }

    /** Parent or any sibling bookkeeper of {@code t}'s group already holds this lock. */
    private boolean groupHolds(Transaction t, LockInfo current) {
        TransactionGroup group = t.getGroup();
        if (group == null) {
            return false;
        }
        if (group.getParent() != null && current.holders.contains(group.getParent())) {
            return true;
        }
        for (BookkeeperTransaction sibling : group.getBookkeepers()) {
            if (current.holders.contains(sibling)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public LockDecision requestLock(Transaction t, String res, LockType type) {
        // Divergence localisation - see EngineEventTrace. Recorded BEFORE the decision so the
        // trace captures the request sequence itself, not just its outcomes: if two runs diverge,
        // the first differing line tells you whether the engine asked a different question or got
        // a different answer to the same one.
        if (EngineEventTrace.enabled()) {
            LockInfo existing = lockTable.get(res);
            EngineEventTrace.record("REQ", t.getId(),
                    res + " want=" + type
                            + " holders=" + (existing == null ? 0 : existing.holders.size())
                            + " held=" + (existing == null ? "-" : existing.type));
        }
        LockInfo current = lockTable.get(res);

        boolean conflicted = !canGrant(t, current, type);
        // Conflict probability per access, counted HERE because this is the single entry point
        // for a lock request and !canGrant is the conflict itself - independent of which wait
        // policy is installed.
        if (metricsLogger != null) {
            metricsLogger.metrics().recordLockRequest(conflicted);
        }
        if (conflicted) {
            countUpgradeDenial(t, current, type);
            return waitPolicy.evaluate(t, current.holders);
        }

        if (current == null) {
            lockTable.put(res, new LockInfo(type, t));
            locksHeldBy.computeIfAbsent(t, k -> new LinkedHashSet<>()).add(res);
            removeFromWaiters(t, res);
            recordAcquired();
            return new GrantedDecision();
        }

        if (current.holders.contains(t)) {
            // canGrant already established this is either sufficient as-is or a sole-holder
            // upgrade, so the only work left is the mode change.
            if (current.type == LockType.SHARED && type == LockType.EXCLUSIVE) {
                current.type = LockType.EXCLUSIVE;
                upgradesGranted++;
            }
            removeFromWaiters(t, res);
            return new GrantedDecision();
        }

        if (groupHolds(t, current)) {
            // Granted WITHOUT joining holders: the group already owns this lock, and the member
            // that acquired it is the one that will release it. Long-standing behaviour, preserved.
            removeFromWaiters(t, res);
            return new GrantedDecision();
        }

        // Shared read-read: join the existing readers.
        current.holders.add(t);
        locksHeldBy.computeIfAbsent(t, k -> new LinkedHashSet<>()).add(res);
        // Leak fix: every grant branch must clear the requester's stale wait-tracker registration.
        // This branch was once the sole exception, which left permanently "waiting" entries for any
        // transaction that had been soft-denied on this lock and later joined it as a reader - a
        // 5-second run showed 61485 stale waiters against an empty lock table, and
        // CautiousWaitingPolicy.isBlocked()'s linear scan reads that same structure.
        removeFromWaiters(t, res);
        recordAcquired();
        return new GrantedDecision();
    }

    private void recordAcquired() {
        if (waitPolicy instanceof AdaptiveWaitPolicy) {
            AdaptiveConcurrencyManager.getInstance().recordLockAcquired();
        }
        MplAdaptationManager.getInstance().recordLockAcquired();
    }

    @Override
    public void releaseLocks(Transaction t) {
        List<String> toRemove = new ArrayList<>();
        Set<Transaction> txnsToRetry = new LinkedHashSet<>();

        // If transaction is being aborted, also clear it from all waiters lists
        if (t.getState() == TransactionState.ABORTED && waitTracker instanceof MapBackedLockWaitTracker) {
            MapBackedLockWaitTracker tracker = (MapBackedLockWaitTracker) waitTracker;
            // Remove from all locks it might be waiting on
            for (String lockId : tracker.getLocksWithWaiters()) {
                tracker.clearWaiting(t, lockId);
            }
        }

        // Scalability fix: only the locks t itself holds (via the reverse index), not a scan of
        // every lock in the system - see locksHeldBy's javadoc above.
        Set<String> heldByT = locksHeldBy.remove(t);
        if (heldByT != null) {
            for (String lockId : heldByT) {
                LockInfo info = lockTable.get(lockId);
                if (info == null) {
                    continue; // Defensive: already removed by some other path
                }
                boolean wasRemoved = info.holders.remove(t);
                if (wasRemoved) {
                    if (waitPolicy instanceof AdaptiveWaitPolicy) {
                        AdaptiveConcurrencyManager.getInstance().recordLockReleased();
                    }
                    MplAdaptationManager.getInstance().recordLockReleased();
                }
                if (info.holders.isEmpty()) {
                    toRemove.add(lockId);

                    // Wake admission waiters for this lock (event-driven admission)
                    if (admissionWaitManager != null) {
                        admissionWaitManager.wakeWaiters(lockId);
                    }

                    // Collect transactions waiting for this specific lock (existing deadlock wait)
                    if (waitTracker instanceof MapBackedLockWaitTracker) {
                        MapBackedLockWaitTracker tracker = (MapBackedLockWaitTracker) waitTracker;
                        Set<Transaction> lockWaiters = tracker.getWaitersForLock(lockId);
                        txnsToRetry.addAll(lockWaiters);
                        // Leak fix (dynamic/bookkeeper config): TransactionLockCoordinator.
                        // blockGroupMember() registers EVERY member of a blocked group as a
                        // waiter on this same lockId - not just the transaction that actually
                        // requested it - purely so this retry-notification wakes the whole group
                        // together (see its "CRITICAL FIX" comment). Every OTHER path that clears
                        // a waiter registration (requestLock()'s grant branches, the ABORTED-state
                        // block above) only fires for a transaction that itself re-attempts
                        // requestLock() or is directly aborted. A sibling member woken here whose
                        // own phase isn't LOCK (e.g. a parent already at phase=COMPLETE, sitting
                        // in the waiting room, blocked only as a side effect of a bookkeeper's
                        // denial) never calls requestLock() again on this lock - so without this,
                        // its registration is permanently orphaned the moment it resumes.
                        // Confirmed via VictimTraceLog replay (S2PLDynamicSanityCheck, dynamic k=10
                        // config): a full group family (parent + bookkeepers, incl. a depth-2
                        // nested one) all resumed through exactly this retry path at phase=COMPLETE/
                        // IO/CPU and later showed up as stale COMPLETED waiters on the lock they
                        // were sibling-blocked on (98.6% of 785 sampled waiters were stale).
                        // Symmetric with the grant-branch cleanup: this lock's wait-list for these
                        // transactions is being resolved right now (they're being handed back for
                        // retry), so clear it here rather than relying on a re-acquisition that
                        // members outside the LOCK phase never perform.
                        for (Transaction w : lockWaiters) {
                            tracker.clearWaiting(w, lockId);
                        }
                    }
                }
            }
        }

        for (String key : toRemove) {
            lockTable.remove(key);
        }

        // Expand txnsToRetry to include ALL members of affected transaction groups
        // This ensures group consistency - when one member can retry, entire group
        // retries
        if (!txnsToRetry.isEmpty() && registry != null) {
            Set<Transaction> expandedRetry = new LinkedHashSet<>();
            for (Transaction txn : txnsToRetry) {
                TransactionGroup group = registry.get(txn);
                if (group != null) {
                    // Add all group members
                    expandedRetry.addAll(group.getAllMembers());
                } else {
                    // Solo transaction or not in registry
                    expandedRetry.add(txn);
                }
            }
            txnsToRetry = expandedRetry;
        }

        // Notify lifecycle manager to retry transactions (now including entire groups)
        if (lifecycleManager != null && !txnsToRetry.isEmpty()) {
            lifecycleManager.retrySpecificTransactions(txnsToRetry);
        }
    }

    @Override
    public void abortTransactionGroup(TransactionGroup group) {
    }

    @Override
    public boolean causesDeadlock(Transaction t, String blockedOn) {
        return false; // Optional
    }

    /**
     * Check if a transaction holds any locks.
     * Used by deadlock detection to identify transactions that are partially
     * through lock acquisition.
     */
    @Override
    public boolean holdsAnyLocks(Transaction t) {
        Set<String> held = locksHeldBy.get(t);
        return held != null && !held.isEmpty();
    }

    @Override
    public String checkLocksAvailable(Transaction t, Map<String, LockType> requested) {
        // First lock this transaction could not have right now, or null if it could have all of
        // them. Mode-aware via canGrant, so a SHARED requester is not blocked by SHARED holders -
        // which a holders.isEmpty() test would treat as a conflict.
        for (Map.Entry<String, LockType> entry : requested.entrySet()) {
            if (!canGrant(t, lockTable.get(entry.getKey()), entry.getValue())) {
                return entry.getKey();
            }
        }
        return null;
    }

    @Override
    public boolean tryAcquireAllLocks(Transaction t, Map<String, LockType> requested) {
        // C2PL preclaim: feasibility for EVERY lock first, then acquire all of them - atomic
        // all-or-nothing. Same canGrant as requestLock and checkLocksAvailable.
        for (Map.Entry<String, LockType> entry : requested.entrySet()) {
            LockInfo current = lockTable.get(entry.getKey());
            if (canGrant(t, current, entry.getValue())) {
                continue;
            }
            countUpgradeDenial(t, current, entry.getValue());
            LockDecision decision = waitPolicy.evaluate(t, current.holders);
            if (!decision.granted() && decision instanceof HardDenyDecision hardDeny) {
                // A null logger is SAFE here because HardDenyDecision.handle() guards its own
                // dereferences; an unguarded one would make this call an NPE on the first hard
                // deny reached via the preclaim path - rare rather than unreachable, since it
                // needs a preclaim to find a lock whose holder is itself blocked. The abort
                // happens either way; only logging is skipped. This class has no logger to pass.
                hardDeny.handle(t, entry.getKey(), this, queueManager, null);
            }
            return false;
        }

        for (Map.Entry<String, LockType> entry : requested.entrySet()) {
            String lockId = entry.getKey();
            LockInfo current = lockTable.get(lockId);
            if (current == null) {
                // Mode comes from the request, not from txn.getLockType(): with per-operation modes
                // a transaction no longer has ONE mode, and a read-only footprint must take SHARED
                // locks that other readers can join.
                lockTable.put(lockId, new LockInfo(entry.getValue(), t));
                locksHeldBy.computeIfAbsent(t, k -> new LinkedHashSet<>()).add(lockId);
                removeFromWaiters(t, lockId);
                recordAcquired();
            } else if (current.holders.contains(t)) {
                if (current.type == LockType.SHARED && entry.getValue() == LockType.EXCLUSIVE) {
                    current.type = LockType.EXCLUSIVE;
                    upgradesGranted++;
                }
                removeFromWaiters(t, lockId);
            } else if (!groupHolds(t, current)) {
                // Compatible shared reader: join the holder set, so H counts it and release frees it.
                current.holders.add(t);
                locksHeldBy.computeIfAbsent(t, k -> new LinkedHashSet<>()).add(lockId);
                removeFromWaiters(t, lockId);
                recordAcquired();
            }
        }
        return true;
    }

    /** An upgrade that only co-readers prevented - contention per-operation modes create and
     * whole-transaction modes cannot. Counted separately from ordinary conflicts. */
    private void countUpgradeDenial(Transaction t, LockInfo current, LockType requested) {
        if (current != null && current.holders.contains(t)
                && current.type == LockType.SHARED && requested == LockType.EXCLUSIVE) {
            upgradesDenied++;
        }
    }

    /** Current mode of a lock, or null if unheld. Exposed so tests can assert that a denied
     * upgrade left the mode alone - the silent-grant bug changed nothing observable except this. */
    public LockType getLockMode(String lockId) {
        LockInfo info = lockTable.get(lockId);
        return info == null ? null : info.type;
    }

    public long getUpgradesGranted() { return upgradesGranted; }

    public long getUpgradesDenied() { return upgradesDenied; }

    /**
     * Get the number of locks currently held.
     */
    public int getLocksHeld() {
        // Held lock UNITS - one per (holder, lock) pair - not entries in the lock table. With
        // shared locks a resource can have several holders, and counting table entries would
        // undercount exactly when S/S sharing is doing something. Identical to the old
        // lockTable.size() whenever every lock has one holder, which is every all-EXCLUSIVE run.
        int units = 0;
        for (Set<String> held : locksHeldBy.values()) {
            units += held.size();
        }
        return units;
    }

    /**
     * TGS Postgres-anchor comparison (H, the held-lock-unit count): production wiring
     * (DefaultLockingBundle) always wraps this class in a BudgetedLockManagerDecorator, so a
     * direct {@code instanceof SS2PLLockManager} check on a LockManager reference silently and
     * always misses. Shared unwrap helper - ACIDAdmissionController.findSS2PLLockManager() had
     * this same logic privately (kept there as well, to avoid a call-site churn this session
     * doesn't need); this copy exists so SimulationMonitor (a different package, no existing
     * dependency on execution) can reach getLocksHeld() for the sampled-H metric without a new
     * cross-package coupling to ACIDAdmissionController specifically.
     */
    public static SS2PLLockManager unwrap(LockManager lockManager) {
        if (lockManager instanceof SS2PLLockManager ss2pl) {
            return ss2pl;
        }
        if (lockManager instanceof BudgetedLockManagerDecorator decorator) {
            return unwrap(decorator.getDelegate());
        }
        return null;
    }
    /**
     * Get all unique transactions currently holding locks.
     */
    public Set<Transaction> getAllLockHolders() {
        Set<Transaction> holders = new LinkedHashSet<>();
        for (LockInfo info : lockTable.values()) {
            holders.addAll(info.holders);
        }
        return holders;
    }

    /**
     * Get detailed lock statistics.
     */
    public String getLockStatistics() {
        Set<Transaction> holders = getAllLockHolders();
        int totalWaiters = getTotalWaitersCount();

        return String.format("Locks held: %d, Unique holders: %d, Total waiters: %d",
                lockTable.size(), holders.size(), totalWaiters);
    }

    /**
     * Get count of locks with waiters (for debugging).
     */
    public int getLockWaitersMapSize() {
        return waitTracker.locksWithWaitersCount();
    }

    /**
     * Get total count of all waiters across all locks (for debugging).
     */
    public int getTotalWaitersCount() {
        return waitTracker.totalWaitersCount();
    }

    /**
     * Get the lock waiters map for deadlock resolution.
     * Returns an immutable view to protect internal state.
     */
    public Map<String, Set<Transaction>> getLockWaiters() {
        if (waitTracker instanceof MapBackedLockWaitTracker) {
            MapBackedLockWaitTracker tracker = (MapBackedLockWaitTracker) waitTracker;
            Map<String, Set<Transaction>> result = new LinkedHashMap<>();
            for (String lockId : tracker.getLocksWithWaiters()) {
                result.put(lockId, tracker.getWaitersForLock(lockId));
            }
            return Collections.unmodifiableMap(result);
        }
        return Collections.emptyMap();
    }

    /**
     * Get the set of transactions holding a specific lock (for debugging).
     */
    @Override
    public Set<Transaction> getLockHolders(String lockId) {
        LockInfo info = lockTable.get(lockId);
        if (info == null) {
            return new LinkedHashSet<>();
        }
        return new LinkedHashSet<>(info.holders);
    }

    /**
     * Diagnostic only: every lock ID currently in the lock table (has at least one holder).
     * Combined with getLockHolders(lockId) and, when the wait tracker is map-backed,
     * getLocksWithWaiters()/getWaitersForLock(lockId), this is enough to reconstruct the full
     * wait-for graph at a watchdog stall - see Simulator.dumpDeadlockStateIfEnabled().
     */
    public Set<String> getLockIdsWithHolders() {
        return new HashSet<>(lockTable.keySet());
    }

    /**
     * Get all unique transactions that are waiting for any lock (for debugging).
     */
    public Set<Transaction> getAllUniqueWaiters() {
        if (waitTracker instanceof MapBackedLockWaitTracker) {
            MapBackedLockWaitTracker tracker = (MapBackedLockWaitTracker) waitTracker;
            Set<Transaction> allWaiters = new LinkedHashSet<>();
            for (String lockId : tracker.getLocksWithWaiters()) {
                allWaiters.addAll(tracker.getWaitersForLock(lockId));
            }
            return allWaiters;
        }
        return new LinkedHashSet<>();
    }

    /**
     * [HERE01]
     * WFG-based Deadlock Detection.
     * Builds a wait-for-graph and uses DFS to detect cycles. If a cycle is found, aborts a victim transaction.
     */
    public void detectAndResolveDeadlocks(long currentTime, TransactionScheduler scheduler, SimulationLogger logger) {
        Map<String, Set<Transaction>> lockWaiters = getLockWaiters();
        if (lockWaiters == null || lockWaiters.isEmpty()) {
            return;
        }

        // Build Wait-For-Graph: Transaction -> set of Transactions it is waiting for
        Map<Transaction, Set<Transaction>> wfg = new LinkedHashMap<>();
        for (Map.Entry<String, Set<Transaction>> entry : lockWaiters.entrySet()) {
            String lockId = entry.getKey();
            Set<Transaction> waiters = entry.getValue();
            Set<Transaction> holders = getLockHolders(lockId);

            if (waiters != null && !waiters.isEmpty() && holders != null && !holders.isEmpty()) {
                for (Transaction waiter : waiters) {
                    wfg.computeIfAbsent(waiter, k -> new LinkedHashSet<>()).addAll(holders);
                }
            }
        }

        if (wfg.isEmpty()) return;

        Set<Transaction> visited = new LinkedHashSet<>();
        Set<Transaction> recursionStack = new LinkedHashSet<>();

        for (Transaction node : wfg.keySet()) {
            if (!visited.contains(node)) {
                List<Transaction> cyclePath = dfsCycleDetect(node, wfg, visited, recursionStack);
                if (cyclePath != null) {
                    Collections.reverse(cyclePath);
                    
                    // Slice out only the cycle portion
                    Transaction cycleStartNode = cyclePath.get(cyclePath.size() - 1);
                    int startIndex = cyclePath.indexOf(cycleStartNode);
                    List<Transaction> cycle = cyclePath;
                    if (startIndex != -1 && startIndex < cyclePath.size() - 1) {
                        cycle = cyclePath.subList(startIndex, cyclePath.size());
                    }

                    Transaction victim = selectVictim(cycle);
                    
                    System.err.println("\n[DEADLOCK DETECTOR] Cycle detected: " + formatCycle(cycle));
                    System.err.println("[DEADLOCK DETECTOR] Aborting victim: " + victim.getId());
                    
                    if (victim.getGroup() != null) {
                        try {
                            victim.getGroup().abortAll(queueManager, this, scheduler, currentTime, logger, null);
                            logger.logEvent(new LogEvent(LogEvent.Type.TRANSACTION_ABORTED, victim.getId(), 0, "name=aborted_deadlock delta=1"));
                            logger.metrics().incrementAborted();
                            logger.metrics().recordAbortReason("deadlock");
                        } catch (Exception e) {
                            System.err.println("[DEADLOCK DETECTOR] Error aborting victim: " + e.getMessage());
                        }
                    }
                    
                    // Break after resolving one deadlock to allow the simulation simulation to advance.
                    // Subsequent cycles (if any) will be caught in the next detection pass.
                    break;
                }
            }
        }
    }

    private List<Transaction> dfsCycleDetect(Transaction node, Map<Transaction, Set<Transaction>> wfg, Set<Transaction> visited, Set<Transaction> recursionStack) {
        visited.add(node);
        recursionStack.add(node);

        Set<Transaction> neighbors = wfg.get(node);
        if (neighbors != null) {
            for (Transaction neighbor : neighbors) {
                if (!visited.contains(neighbor)) {
                    List<Transaction> cycle = dfsCycleDetect(neighbor, wfg, visited, recursionStack);
                    if (cycle != null) {
                        cycle.add(node);
                        return cycle;
                    }
                } else if (recursionStack.contains(neighbor)) {
                    List<Transaction> cycle = new ArrayList<>();
                    cycle.add(neighbor);
                    cycle.add(node);
                    return cycle;
                }
            }
        }

        recursionStack.remove(node);
        return null;
    }

    private Transaction selectVictim(List<Transaction> cycle) {
        Transaction youngest = null;
        int maxIdNum = -1;
        for (Transaction t : cycle) {
            if (t == null) continue;
            try {
                String idStr = t.getId().replaceAll("[^0-9]", "");
                if (!idStr.isEmpty()) {
                    int idNum = Integer.parseInt(idStr);
                    if (idNum > maxIdNum) {
                        maxIdNum = idNum;
                        youngest = t;
                    }
                }
            } catch (Exception e) {}
        }
        return youngest != null ? youngest : cycle.get(0);
    }

    private String formatCycle(List<Transaction> cycle) {
        StringBuilder sb = new StringBuilder();
        for (Transaction t : cycle) {
            if (t != null) {
                sb.append(t.getId()).append(" -> ");
            }
        }
        if (sb.length() > 4) {
             sb.setLength(sb.length() - 4);
        }
        return sb.toString();
    }
}

