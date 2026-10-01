package de.tum.tssimulator.core;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import de.tum.tssimulator.bookkeeping.BookkeeperTransaction;
import de.tum.tssimulator.execution.CapacityWaitManager;
import de.tum.tssimulator.execution.PhaseDispatcherGroup;
import de.tum.tssimulator.lifecycle.TransactionGroupAbortCoordinator;
import de.tum.tssimulator.locking.core.LockManager;
import de.tum.tssimulator.monitoring.LogEvent;
import de.tum.tssimulator.monitoring.SimulationLogger;
import de.tum.tssimulator.queue.QueueManager;
import de.tum.tssimulator.queue.QueueType;
import de.tum.tssimulator.scheduling.TransactionScheduler;

/**
 * Represents a group of related transactions (parent + bookkeepers).
 * Refactored to focus on group state management only.
 * Abort coordination delegated to TransactionGroupAbortCoordinator.
 */
public class TransactionGroup {
    private static final AtomicLong ID_GENERATOR = new AtomicLong(0);

    /**
     * Reset the process-global group-id sequence, so a run's ids do not depend on how many runs
     * preceded it in this JVM. Called from {@code Simulator}'s constructor alongside
     * {@code Transaction.resetMetrics()} and {@code TransactionStateCounter.reset()}, which were
     * already reset there; this one was not, and it is the only remaining unreset static that
     * feeds run behaviour rather than only reporting.
     */
    public static void resetIdGenerator() {
        ID_GENERATOR.set(0);
    }
    private final long groupId = ID_GENERATOR.getAndIncrement();
    private volatile boolean capacityWaiterWoken = false;
    
    private Transaction parent;
    private final List<BookkeeperTransaction> bookkeepers = new ArrayList<>();
    private TransactionGroupState state = TransactionGroupState.ACTIVE;
    private int completedCount = 0;
    private boolean markedForUserAction = false;
    
    // Instrumental metrics
    private int attemptedBookkeepers = 0;
    private final int[] bookkeepersAtDepth = new int[9]; // 0-7, 8 is overflow
    // Exact (not bucketed/lossy like bookkeepersAtDepth's depth>=8 catch-all) deepest bookkeeper
    // depth seen in this group - the per-group cascade-depth signal for correlating starvation
    // incidence with cascade depth (schema-implied max depth can exceed 8, see
    // CascadeDepthAnalyticalCheck: k=25/k=50 schemas reach BFS depth 15-18).
    private int maxBookkeeperDepth = 0;

    // Kaplan-Meier censoring: TransactionFactory.restartTransaction()
    // creates a BRAND-NEW TransactionGroup for every restart, abandoning this one - but the
    // registry never removes this (now-stale) group's entry (TransactionGroupRegistry.clear()
    // only runs on the COMPLETION path), so a naive "is this group still open" check has to tell
    // stale/abandoned groups apart from genuinely still-open ones some other way. Filtering by
    // parent.getState() != ABORTED doesn't work: a transaction sitting in the exponential-backoff
    // queue between restarts (ABORTED_BACKOFF) is ALSO in TransactionState.ABORTED at that
    // instant, indistinguishable via getState() alone from a lineage that has permanently ended.
    // This flag is set explicitly, once, exactly when a lineage is abandoned in favor of a new
    // attempt (TransactionGroupAbortCoordinator.restartTransaction()) - independent of whatever
    // transient state the old parent's Transaction object is in.
    private boolean superseded = false;
    
    // Dispatcher for clearing transactions from processors during state transitions
    private PhaseDispatcherGroup dispatcher;

    // Unit AFFINITY, not a dedicated processor - see CentralQueueManager's field comment on
    // pooledOwnQueues for the full mechanism. Set once, the first time any member of this group
    // is assigned to an IO/CPU processor unit (SimulatedProcessorUnit.assign()); every later
    // member of the SAME group is routed to the SAME unit's own queue instead of the shared pool,
    // so a group's own members cannot occupy multiple units simultaneously. That is the measured
    // cause of the S2PL-dynamic-vs-S2PL-oracle low-N throughput crossover: the monolithic oracle
    // transaction can only ever hold one unit, so a multi-member group getting real cross-unit
    // parallelism is an artifact of the oracle control's construction rather than a protocol
    // effect - see BookkeeperParallelismCheck. -1 means unpinned. Never reset:
    // every TransactionGroup is a fresh object for its entire lifetime (only two construction
    // sites in production code, TransactionScheduler for new admissions and
    // TransactionFactory.restartTransaction() for retries, and the latter always builds a
    // brand-new TransactionGroup - see the superseded field above - so no code path can carry a
    // stale pin onto a reused group).
    private int pinnedIoUnitIndex = -1;
    private int pinnedCpuUnitIndex = -1;
    // THE LOCK POOL NEEDS PINNING TOO, which LockDispatchParallelismCheck does not show:
    // that check establishes only that a SINGLE transaction's own lock request is serial, not
    // that two MEMBERS of one group cannot occupy two LOCK units simultaneously. They can -
    // BookkeeperParallelismCheck measures maxBusyUnits=6 and 62% of dispatch calls with >1 busy
    // unit on the LOCK pool for a k=50 dynamic group at mpl=1, while oracle stays at 1. The
    // arithmetic implies it independently: summed member lock time per completed group (~265ms)
    // exceeds that group's entire lifetime (~170ms), which is impossible without overlap.
    private int pinnedLockUnitIndex = -1;

    public TransactionGroup() {
    }

    public TransactionGroup(Transaction parent) {
        this.parent = parent;
    }

    public void setParent(Transaction parent) {
        this.parent = parent;
    }
    
    /**
     * Inject the phase dispatcher for clearing transactions from processors.
     * Called by TransactionGroupRegistry when registering groups.
     */
    public void setDispatcher(PhaseDispatcherGroup dispatcher) {
        this.dispatcher = dispatcher;
    }

    public Transaction getParent() {
        return this.parent;
    }

    /**
     * -1 if this group has no affinity pin yet for the given pooled QueueType (IO or CPU only -
     * callers for any other type get -1 unconditionally, there is nothing to pin).
     */
    public int getPinnedUnitIndex(QueueType type) {
        return switch (type) {
            case IO -> pinnedIoUnitIndex;
            case CPU -> pinnedCpuUnitIndex;
            case LOCK -> pinnedLockUnitIndex;
            default -> -1;
        };
    }

    /**
     * First write wins. Called from SimulatedProcessorUnit.assign() every time a member of this
     * group is assigned to an IO/CPU unit - a no-op once the pin is already set (which, per the
     * mechanism this exists for, should always be by the parent, since a CU is only ever
     * triggered after its parent finishes - but this doesn't assume that ordering, it just keeps
     * whichever pin was set first). Single-threaded simulation loop - see CentralQueueManager's
     * class comment - so no synchronization needed.
     */
    public void pinUnitIfAbsent(QueueType type, int candidateUnitIndex) {
        switch (type) {
            case IO -> { if (pinnedIoUnitIndex < 0) pinnedIoUnitIndex = candidateUnitIndex; }
            case CPU -> { if (pinnedCpuUnitIndex < 0) pinnedCpuUnitIndex = candidateUnitIndex; }
            case LOCK -> { if (pinnedLockUnitIndex < 0) pinnedLockUnitIndex = candidateUnitIndex; }
            default -> { /* not a pooled type - nothing to pin */ }
        }
    }

    /**
     * The single group member currently occupying an IO or CPU unit, or null.
     *
     * <p>groupUnitAffinity pins the group per POOL, which serialises members within IO and
     * within CPU but NOT across them - so without this, member A can be in CPU while member B
     * is in IO, giving the cascade an effective parallelism of 2. That is a two-stage pipeline
     * and it shows up as per-CU cost {@code max(io,cpu) + min(io,cpu)/m} rather than
     * {@code io + cpu}.
     *
     * <p>LOCK is deliberately NOT covered: lock acquisition is not processor work, so a
     * member's LOCK phase may overlap a sibling's IO/CPU. That is why this is a dispatcher
     * guard rather than enqueueing one member at a time, which would remove the exemption.
     *
     * <p>Single-threaded simulation loop, so no synchronization.
     */
    private Transaction processorOccupant = null;

    /** True when some OTHER member of this group holds an IO/CPU unit right now. */
    public boolean isProcessorBusyForOther(Transaction candidate) {
        return processorOccupant != null && processorOccupant != candidate;
    }

    /**
     * Called when a member is assigned to an IO or CPU unit.
     *
     * <p>Throws rather than overwriting, and the check is ALWAYS ON: it is one reference
     * comparison, and a property-gated check would be off in exactly the campaigns that matter.
     * Only {@code CentralQueueManager.dequeueOwn} consults occupancy, so a member reaching the
     * SHARED dequeue -- e.g. a group with no pin yet for that pool -- would otherwise silently
     * overwrite the occupant and restore cross-pool pipelining with no visible symptom. This
     * assertion is the field-level acceptance check for that path; a throughput delta could
     * match for the wrong reason.
     */
    public void occupyProcessor(Transaction txn) {
        occupyProcessor(txn, true);
    }

    /**
     * @param enforceSingleOccupancy whether the one-member-per-group rule is in force, i.e.
     *        {@code groupUnitAffinity}. <b>The invariant asserts exactly what affinity=true
     *        guarantees, so it can only hold when the flag is set.</b> Ungated, it fired the
     *        moment the flag did the thing it exists to do - two members of a group running
     *        concurrently - which made {@code groupUnitAffinity=false} unrunnable and silently
     *        turned a measurable contrast ("does CU work parallelise?") into an assumption fixed
     *        by a default. {@code GroupUnitAffinityTest} could not see it: it asserts QUEUE
     *        ROUTING and never runs a simulation, so it never reaches this method.
     *
     *        <p>The occupant is still WRITTEN either way, exactly as
     *        {@code SimulatedProcessorUnit.assign} keeps the pin unconditional - only
     *        {@code CentralQueueManager.dequeueOwn} reads occupancy and it is already gated on the
     *        same flag, so an unread occupant has no effect.
     */
    public void occupyProcessor(Transaction txn, boolean enforceSingleOccupancy) {
        if (enforceSingleOccupancy && processorOccupant != null && processorOccupant != txn) {
            throw new IllegalStateException(
                "Group " + (parent == null ? "?" : parent.getId())
                + ": member " + txn.getId() + " took an IO/CPU unit while member "
                + processorOccupant.getId() + " still holds one. A group's cascade must occupy "
                + "at most one processor across IO union CPU - see processorOccupant.");
        }
        processorOccupant = txn;
    }

    /**
     * Count of this group's members currently `current` on an IO or CPU unit.
     *
     * <p>Independent of {@link #processorOccupant}, and deliberately so. The occupant is slot
     * BOOKKEEPING, released by lifecycle events; this is measured at the units themselves,
     * incremented in assign() and decremented in releaseCurrent(). If a release site (blockAll,
     * abortGroup) nulls the slot while a member is still `current` on a unit, the occupant
     * invariant cannot fire -- it only checks a non-null occupant -- and a sibling would take the
     * slot and run concurrently with no symptom. This counter catches exactly that.
     */
    private int unitsHeld = 0;

    /**
     * How many times this group has been force-blocked via {@link #blockAll}. Instrumentation
     * only: without it, a smoke run cannot tell "the block/resume path never executed" from
     * "it executed and nothing went wrong", and `blocked_at_check_rate` does not answer it --
     * that counts AdmissionWaitManager preclaim waits, which never reach blockAll or the slot.
     */
    private int blockAllInvocations = 0;

    public int getBlockAllInvocations() {
        return blockAllInvocations;
    }

    /** Incremented when a member is assigned to an IO/CPU unit. Throws if the group exceeds one. */
    public void enterProcessorUnit(Transaction txn) {
        enterProcessorUnit(txn, true);
    }

    /**
     * @param enforceSingleOccupancy see {@link #occupyProcessor(Transaction, boolean)} - the
     *        throw encodes the {@code groupUnitAffinity=true} guarantee and must not outlive it.
     *
     *        <p>{@code unitsHeld} is counted either way, so increments stay paired with
     *        {@link #leaveProcessorUnit}'s decrements. Gating the COUNTER instead of the throw
     *        would underflow on release, since {@code SimulatedProcessorUnit.releaseCurrent()} is
     *        lifecycle-driven and has no view of the config.
     */
    public void enterProcessorUnit(Transaction txn, boolean enforceSingleOccupancy) {
        unitsHeld++;
        if (enforceSingleOccupancy && unitsHeld > 1) {
            throw new IllegalStateException(
                "Group " + (parent == null ? "?" : parent.getId()) + ": " + unitsHeld
                + " members on IO/CPU units at once (latest " + txn.getId()
                + "). A group's cascade must occupy at most one processor across IO union CPU.");
        }
    }

    /** Decremented when a member leaves an IO/CPU unit. Floors at zero. */
    public void leaveProcessorUnit() {
        // Throws on underflow rather than clamping: clamping hides double-decrements, which is
        // the mirror image of the bug this counter exists to catch.
        if (unitsHeld <= 0) {
            throw new IllegalStateException(
                "Group " + (parent == null ? "?" : parent.getId())
                + ": leaveProcessorUnit() with unitsHeld=" + unitsHeld
                + " -- a member left an IO/CPU unit it never entered, or left twice.");
        }
        unitsHeld--;
    }

    /**
     * Drops the slot regardless of who holds it. For group-wide terminal events (abort,
     * rollback) where the holder may be any member and may be on no unit at all.
     */
    public void releaseProcessorUnconditionally() {
        processorOccupant = null;
    }

    /** Called when a member leaves an IO or CPU unit. Idempotent, and ignores a stale owner. */
    public void releaseProcessor(Transaction txn) {
        if (processorOccupant == txn) {
            processorOccupant = null;
        }
    }

    public void addBookkeeper(BookkeeperTransaction bk) {
        bookkeepers.add(bk);
    }

    public void addBookkeepers(List<BookkeeperTransaction> bks) {
        bookkeepers.addAll(bks);
    }

    public List<BookkeeperTransaction> getBookkeepers() {
        return bookkeepers;
    }
    
    public void addAttemptedBookkeepers(int count) {
        this.attemptedBookkeepers += count;
    }
    
    public int getAttemptedBookkeepers() {
        return attemptedBookkeepers;
    }
    
    public void recordBookkeeperDepth(int depth) {
        if (depth >= 0 && depth < 8) {
            bookkeepersAtDepth[depth]++;
        } else if (depth >= 8) {
            bookkeepersAtDepth[8]++;
        }
        if (depth > maxBookkeeperDepth) {
            maxBookkeeperDepth = depth;
        }
    }

    public int[] getBookkeepersAtDepth() {
        return bookkeepersAtDepth;
    }

    public int getMaxBookkeeperDepth() {
        return maxBookkeeperDepth;
    }

    public void markSuperseded() {
        superseded = true;
    }

    public boolean isSuperseded() {
        return superseded;
    }

    public List<Transaction> getAllMembers() {
        List<Transaction> members = new ArrayList<>();
        if (parent != null) {
            members.add(parent);
        }
        members.addAll(bookkeepers);
        return members;
    }

    public void clearBookkeepers() {
        bookkeepers.clear();
    }

    public void markBookkeeperCompleted() {
        completedCount++;
        if (isComplete()) {
            state = TransactionGroupState.COMPLETED;
        }
    }

    /**
     * S2PL/deadlock-policy follow-up fix: the ONLY other writer of TransactionGroupState.
     * COMPLETED was markBookkeeperCompleted() above, reachable exclusively via
     * ACIDCompletionHandler.completeBookkeeper() - i.e. only for groups that actually created at
     * least one bookkeeper AND completed via the bookkeeper-completion path. A group that
     * completes with ZERO bookkeepers (completeSingleTransaction()) or completes atomically with
     * bookkeepers already present (completeGroupAtomically()) never called either path, so state
     * stayed ACTIVE forever after real completion - a latent desync between TransactionGroupState
     * and the parent Transaction's own (correctly terminal) TransactionState.COMPLETED. Every
     * existing state==COMPLETED guard (abortGroup, abortHardDeniedGroup, HardDenyDecision,
     * handleLockDenial's isInitialLockPhase gate) assumed this desync couldn't happen - true
     * under C2PL preclaim, where a parent's own acquireAllLocks() call always finds every lock
     * already self-held and never reaches handleLockDenial()/abortWaitersBlockedByGroup() at
     * all. S2PL mode (ACIDAdmissionController.ENABLE_C2PL=false) exercises that path for
     * ordinary (often zero-bookkeeper) parents AND for bookkeepers whose sibling/parent group
     * already completed, and abortWaitersBlockedByGroup() found a completed-but-still-ACTIVE
     * group and tried to re-abort it - crashing on parent.transitionTo(ROLLBACK_QUEUED) from the
     * parent's own terminal COMPLETED state. Called from ACIDCompletionHandler at the actual
     * completion point, on both paths.
     */
    public void markCompleted() {
        state = TransactionGroupState.COMPLETED;
    }

    public boolean isComplete() {
        return completedCount == bookkeepers.size();
    }
    
    public long getGroupId() {
        return groupId;
    }
    
    public boolean hasWokenCapacityWaiter() {
        return capacityWaiterWoken;
    }
    
    public void markCapacityWaiterWoken() {
        this.capacityWaiterWoken = true;
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TransactionGroup)) return false;
        return groupId == ((TransactionGroup) o).groupId;
    }
    
    @Override
    public int hashCode() {
        return Long.hashCode(groupId);
    }
    
    public int getCompletedCount() {
        return completedCount;
    }
    
    public void resetCompletedCount() {
        completedCount = 0;
    }

    public TransactionGroupState getState() {
        return state;
    }
    
    public void setState(TransactionGroupState newState) {
        this.state = newState;
    }
    
    public void markForUserAction() {
        this.markedForUserAction = true;
    }
    
    public boolean isMarkedForUserAction() {
        return markedForUserAction;
    }

    /**
     * Clear all transactions in this group from active processors.
     * Called during state transitions (abort, block) to prevent continued execution.
     */
    /**
     * @param currentTime virtual time of the sweep, or {@link SimulationLogger}'s
     *        {@code LogEvent.NO_VIRTUAL_TIME} when the caller has no clock -- blockAll() does
     *        not, abortAll() does. The sentinel keeps the two distinguishable in the log.
     */
    private void clearFromProcessors(SimulationLogger logger, long currentTime) {
        // Dropped-reference guard (found via targeted replay, seed=1000003/N=16/k=50 shape -
        // see VictimTraceLog / dynamic-footprint-variance-investigation memory): abortAll() and
        // blockAll() both route here unconditionally. TransactionLockCoordinator's
        // abortHardDeniedGroup()/blockEntireTransactionGroup()/abortWaitersBlockedByGroup() only
        // guard against TransactionGroupState.ABORTED, never ABORTING - so any of them can
        // re-fire abortAll()/blockAll() on a group that is ALREADY aborting (its locks aren't
        // released until completeRollback() runs, so another transaction can easily still be
        // waiting on one mid-rollback). Re-firing would evict whichever member is currently
        // `current` in a processor (e.g. the parent mid-ROLLBACK-phase tick) via
        // clearTransactionsByGroup(), which bypasses CentralQueueManager.remove() entirely: no
        // queue re-insertion, no state transition, nothing. The evicted transaction is then
        // permanently orphaned - still counted RUNNING, in no queue, in no processor, never
        // reachable again - which also stalls completeRollback() for that group's bookkeepers.
        // Guarding here (the single choke point both
        // abortAll() and blockAll() go through) fixes every caller at once rather than requiring
        // every future TransactionLockCoordinator call site to remember the ABORTING check.
        if (state == TransactionGroupState.ABORTING || state == TransactionGroupState.ABORTED) {
            if (parent != null) {
                VictimTraceLog.log("CLEAR_FROM_PROCESSORS_BLOCKED_BY_GUARD", parent.getId(),
                        "groupState=" + state + " (re-entrant clear suppressed)");
            }
            // Logged UNCONDITIONALLY, unlike the VictimTraceLog line above which is watch-list
            // gated and therefore invisible unless a transaction is being traced. Zero evictions
            // has three causes -- the path never fired, members were all queued, or this guard
            // suppressed the clear -- and only this event separates the third from the second.
            if (logger != null && parent != null) {
                logger.logEvent(new LogEvent(
                        LogEvent.Type.CLEAR_SUPPRESSED_ABORTING,
                        parent.getId(), 0L, "groupState=" + state));
            }
            return;
        }
        if (dispatcher != null) {
            int cleared = dispatcher.clearTransactionsByGroup(this);
            if (cleared > 0 && logger != null) {
                // ASSERT: parent must exist for active groups
                if (parent == null) {
                    throw new IllegalStateException(
                        "BUG: TransactionGroup " + groupId + " has null parent during clearFromProcessors");
                }
                
                logger.logEvent(new LogEvent(LogEvent.Type.CLEARED_FROM_PROCESSORS,
                    parent.getId(), currentTime, "cleared-from-processors count=" + cleared));
            }
        }
    }
    
    /**
     * Abort all transactions in this group.
     * Delegates to TransactionGroupAbortCoordinator for orchestration.
     */
    public void abortAll(QueueManager queueManager, 
                        LockManager lockManager, 
                        TransactionScheduler scheduler,
                        long currentTime,
                        SimulationLogger logger,
                        CapacityWaitManager capacityWaitManager) {
        // Clear from processors first
        clearFromProcessors(logger, currentTime);
        
        // Delegate to abort coordinator
        TransactionGroupAbortCoordinator coordinator = new TransactionGroupAbortCoordinator();
        coordinator.abortGroup(this, queueManager, lockManager, scheduler, currentTime, logger, capacityWaitManager);
    }
    
    /**
     * Block all transactions in this group (called when group encounters lock contention).
     * Clears transactions from active processors to prevent continued execution.
     */
    public void blockAll(SimulationLogger logger) {
        // A blocked member is not using a processor, so it gives the slot up and re-takes it on
        // resume via the normal assign() path -- there is no explicit retake, and the scan's
        // occupancy predicate handles contention. Holding the slot across a block would mean a
        // group's processor is occupied by something that is not running, inverting the property
        // the slot exists to express.
        processorOccupant = null;
        blockAllInvocations++;
        if (logger != null) {
            // Global tally, plus a loggable event. The CSV route would need the metrics snapshot
            // type extended; an event is greppable now and pairs with the log filter. Without
            // one, zero PHASE_EVICTED cannot be told apart from "force-block never fired" --
            // which is exactly the ambiguity that made the first zero-eviction reading unusable.
            logger.metrics().incrementBlockAllTotal();
            logger.logEvent(new LogEvent(
                    LogEvent.Type.GROUP_FORCE_BLOCKED,
                    parent == null ? "?" : parent.getId(), 0L,
                    "members=" + getAllMembers().size() + " state=" + state + " group=" + groupId));
        }

        // blockAll takes no virtual time, so this sweep genuinely has none to record.
        clearFromProcessors(logger, LogEvent.NO_VIRTUAL_TIME);
    }
}
