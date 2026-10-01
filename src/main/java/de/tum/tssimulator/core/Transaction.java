package de.tum.tssimulator.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import de.tum.tssimulator.core.program.Operation;
import de.tum.tssimulator.core.program.OperationType;
import de.tum.tssimulator.execution.AdmissionAttemptProvenance;
import de.tum.tssimulator.execution.TransactionPhaseExecutor;
import de.tum.tssimulator.locking.core.LockGranularityMapper;
import de.tum.tssimulator.model.IOModel;
import de.tum.tssimulator.queue.QueueType;

public class Transaction {
    private String id;
    private final long timestamp;
    private List<Resource> resources;
    private final List<Operation> operations;
    private final String programName;
    public TransactionGroup group;

    private final TransactionStateMachine fsm = new TransactionStateMachine(this);
    private final TransactionMetrics metrics = new TransactionMetrics();
    private final TransactionPhaseExecutor phaseExecutor = new TransactionPhaseExecutor();

    // NEW: Store completion timestamp for dirty set clearing
    private long completionTime = 0;
    private long submissionTime = 0;
    // submissionTime is reset to "now" on every restart
    // (TransactionScheduler.restartTransaction()) - correct for its original purpose (per-attempt
    // service latency, e.g. TransactionLifecycleManager's latency), but wrong for measuring a
    // GROUP's total lineage age across all its restarts, which is what group-lifetime/Kaplan-Meier
    // analysis (AbGroupLifetimeExperiment) needs. Using submissionTime for that made "lifetime"
    // silently mean "time since the LAST restart" instead of "time since the lineage's first
    // admission" - caught by an impossible result: a retry/backoff decomposition comparing
    // lifetime against CUMULATIVE mandated backoff (itself correctly cumulative, since
    // lockRetryCount IS carried forward across restarts - see TransactionFactory.
    // restartTransaction()) produced deeply NEGATIVE residuals at high retry counts (mandated
    // backoff exceeding the observed "lifetime"), which is only possible if the two are on
    // different time bases. firstAdmissionTime fixes this: set once, at the lineage's true
    // first admission (TransactionScheduler.createTransaction()), and copied forward - never
    // reset - on every restart (TransactionScheduler.restartTransaction()), symmetric to how
    // lockRetryCount is carried forward. submissionTime itself is untouched, so its existing
    // per-attempt-latency consumers see no behavior change.
    private long firstAdmissionTime = 0;

    // lock_seconds_waiting_room instrumentation: hold-interval bookkeeping for locks acquired
    // by *this* transaction (own-acquired locks only - see ACIDAdmissionController). -1 means
    // "not currently tracked" (no locks acquired / already finalized).
    /**
     * One entry per BATCH of locks acquired, retained until release.
     *
     * <p>Replaces a single (start, count) scalar pair. Under interleaving a transaction acquires
     * its locks in several batches at different times, and every batch is held until the SINGLE
     * release at commit - batch 1's locks for the whole transaction, batch k's for almost none. A
     * scalar can only record one of those, and recording per-batch DURATION instead would measure
     * how long each batch took to execute, which is a different quantity entirely and collapses
     * mean hold time toward per-operation service time.
     */
    private final List<HeldBatch> heldBatches = new ArrayList<>();

    /** Locks taken in one acquisition, and when. */
    public record HeldBatch(int lockCount, long acquire) {
    }
    private long waitingRoomEntry = -1;

    // F5 (VLDB reviewer response): lock_wait_p50/p95/p99 instrumentation - distinct from the
    // lock-HOLD tracking above. -1 means "not currently queued for a lock." Set when this
    // member transitions to BLOCKED_TXN (SoftDenyDecision / TransactionLockCoordinator's
    // blockGroupMember()), cleared when it successfully acquires all its locks
    // (TransactionLockCoordinator.acquireAllLocks()).
    private long lockWaitStart = -1;
    // Exponential backoff: track when transaction can retry lock acquisition
    private int lockRetryCount = 0;
    private long earliestRetryTime = 0; // Virtual time when this transaction can retry

    // S2PL soft-deny backoff: DISTINCT from lockRetryCount above, which is a whole-lineage,
    // abort-and-restart counter (incremented once per abort in TransactionFactory.
    // restartTransaction(), carried forward onto a brand-new Transaction object - see its
    // javadoc). This one counts consecutive soft-deny cycles for THIS SAME transaction object,
    // within a single admission lineage, never reset except on a successful reacquisition - see
    // TransactionLifecycleManager.retrySpecificTransactions()/AbstractAdmissionController.
    // processBackoffQueues(). Needed because, once ACIDAdmissionController.admitReadyTransaction()
    // started performing a REAL reacquisition attempt for a woken transaction (instead of just
    // resuming a LOCK-phase timer queue that assumed locks were already held), an
    // immediately-re-denied transaction could bounce BLOCKED<->READY arbitrarily many times
    // within a single dispatch tick with no virtual time ever passing - 9GB of log output for 5
    // simulated seconds at mpl=640, measured. Growing the delay on each consecutive failure, via
    // the same BackoffQueue mechanism the abort-restart path uses, forces genuine virtual time to
    // pass between attempts, which is what actually breaks the loop: the backoff queue is drained
    // by elapsed time (dequeueReady(currentTime)), not by re-entering the same dispatch() call.
    private int softDenyRetryCount = 0;

    // Bug fix companion to softDenyRetryCount above: once that counter goes above 0,
    // ACIDAdmissionController.admitReadyTransaction()'s LOCK-phase branch unconditionally routes
    // EVERY subsequent READY_TXN dequeue back into the backoff queue - there was no way to tell
    // "just got denied, defer for the first time" apart from "backoff period already elapsed,
    // this IS the retry attempt". Since softDenyRetryCount only resets on a SUCCESSFUL
    // reacquisition, and a transaction can only reach a successful reacquisition by first being
    // allowed to actually call processor.process() again, this was a livelock: the counter kept
    // incrementing and the transaction kept getting rescheduled into backoff, forever, without
    // ever taking a real retry attempt again. Confirmed directly via VictimTraceLog replay
    // (TransactionGroupReentrantAbortRegressionTest, seed=1000003): a single bookkeeper
    // (BK0-BK1-BK2-BK0-BK1-T21-RO) cycled ENQUEUE/DEQUEUE READY_TXN dozens of times with
    // softDenyRetryCount climbing and not one further CAPACITY_AVAILABLE transition - its parent
    // group (158 members, ~150 held locks) could then never complete, which is what several other
    // groups were correctly (not buggily) blocked on. This flag is set by
    // AbstractAdmissionController.processBackoffQueues() the moment a transaction is actually
    // drained from the backoff queue (i.e. its delay has genuinely elapsed) and consumed by
    // admitReadyTransaction() to force that specific admission attempt through to
    // processor.process(), regardless of what softDenyRetryCount currently holds.
    private boolean readyForBackoffRetry = false;

    // TGS static-locking validation: counts calls into
    // ACIDAdmissionController.tryAdmitWithLockCheck() for THIS transaction object (mirrors
    // lockRetryCount's carried-forward-across-retries pattern, but this one is NOT reset on
    // restartTransaction() - a restart produces a brand-new Transaction object, so its own
    // counter naturally starts at 0). admissionAttemptProvenance records why the NEXT such call
    // is happening - set by AdmissionWaitManager.wakeWaiters() (WOKEN) or
    // ACIDAdmissionController's [ADMISSION-RACE] branch (RACE_RETRY) immediately before their
    // respective re-enqueue-to-PENDING calls; defaults to FRESH for a transaction's first-ever
    // admission attempt, which no setter has touched yet.
    private int admissionAttemptIndex = 0;
    private AdmissionAttemptProvenance admissionAttemptProvenance = AdmissionAttemptProvenance.FRESH;
    // TGS static-locking validation: virtual time of THIS transaction's first (attemptIndex==0)
    // admission attempt, set once and read back at successful admission to compute the
    // first-attempt-to-admitted delay (SimulationMetrics.recordAdmissionDelay) - the direct test
    // of whether blocking costs any virtual time at all in this engine. -1 means "not yet set."
    private long firstAdmissionAttemptVirtualTime = -1;

    /** Four-way lineage time decomposition: closed accounting of where a lineage's total
     * elapsed time (firstAdmissionTime to now) actually goes - the direct, no-inference-step
     * test of whether a "stuck" lineage is parked in backoff, parked as an admission waiter,
     * sitting in the raw PENDING queue awaiting dispatch, or genuinely running (admitted,
     * whatever it's doing after that - lock/IO/CPU dispatch, bookkeeper-blocked-holding-locks,
     * etc.). Mutually exclusive, jointly exhaustive by construction: every markPhase() call
     * closes exactly the currently-open interval before opening the next, so the four
     * accumulators plus whatever's still open at the observation point sum to elapsed lifetime
     * with no gap and no double-count. Carried forward across restarts via
     * copyLineageTimeAccountingFrom() (same pattern as firstAdmissionTime/lockRetryCount) -
     * parent/lineage-level only, not tracked on bookkeepers. */
    /**
     * WAITING_ROOM exists because RUNNING was an interval that never closed.
     *
     * <p>{@code markPhase(RUNNING)} is set once, at admission, and never re-marked. A parent that
     * creates bookkeepers then sits in {@code moveToWaitingRoom} for its entire cascade with nothing
     * closing that interval, so on any cell with {@code semanticOverlap > 0} the RUNNING bucket was
     * not execution time at all - it stayed open until {@code finalizeLineageTimeAccounting} shut it
     * at the very end, absorbing the whole wait.
     *
     * <p>Separate bucket rather than folded into RUNNING: a waiting-room parent is in flight and
     * holding locks (contention-wise "running") but is blocked on its cascade (behaviourally not),
     * and keeping them apart lets either view be reconstructed. Nothing is taken away from existing
     * consumers by the split, because RUNNING was never measuring execution for these parents.
     *
     * <p>Verified exact on the reentrant-abort cell at HEAD: over all 572 completing attempts,
     * {@code pend 9,056 + back 2,564,685 + adm 267,185 + run 26,371 = 2,867,297}, equal to
     * per-attempt latency to the unit. WAITING_ROOM (1,021,893) sits OUTSIDE that sum by
     * construction - {@code completionTime} is stamped when the transaction finishes CPU, before
     * {@code moveToWaitingRoom} is called - so it is post-latency time, not a missing term in it.
     */
    /**
     * BLOCKED EXISTS BECAUSE RUNNING WOULD OTHERWISE MEAN "ADMITTED", NOT "RUNNABLE".
     *
     * <p>Without it a parent marked RUNNING at admission stays RUNNING while it sits in
     * {@code BLOCKED_TXN} waiting on a lock its group was denied. That leaves the partition
     * sound - every interval still closes - but makes it unreadable to a CONSUMER:
     * {@code WorkloadTimeBreakdownSweep} publishes {@code occRunning - service - wasted} as
     * {@code processor_wait_ticks}, and that residual is genuine processor queueing only when
     * nothing blocks mid-flight.
     *
     * <p>The measurement that forces the split: at 1 core, f = 0.80, the residual puts 20.3
     * parents in RUNNING while the two units are 16.6% busy - work queued at an idle server,
     * which a work-conserving dispatcher cannot produce. The control at the same core count reads
     * 188.6 of 192 in RUNNING with the units ~100% busy, i.e. the residual behaves as its name
     * claims once the CPRs are removed. The problem is therefore in the NAME, not in the
     * scheduler.
     *
     * <p>APPENDED, NOT INSERTED. {@code WorkloadTimeBreakdownSweep} indexes
     * {@code phaseOccupancy} by {@code ordinal()}, and the archived CSVs were written against the
     * original numbering. Keeping the BLOCKED_* constants at the end leaves
     * PENDING_QUEUE..WAITING_ROOM on 0..4, so archived rows and new rows mean the same thing in
     * the same column.
     *
     * <p>SCOPE, stated because it is a real approximation: the interval opens when the member is
     * enqueued to {@code BLOCKED_TXN} and closes in {@code retrySpecificTransactions}, which is
     * the BLOCKED -> READY transition. Time spent sitting in {@code READY_TXN} afterwards waiting
     * to be re-admitted is therefore booked to RUNNING, not to BLOCKED. That is the cheaper of
     * two wrong choices - {@code admitReadyTransaction} calls {@code processor.process()} directly
     * and never reaches the {@code markPhase(RUNNING)} at the admission site, so closing the
     * interval any later would need a new marking on a path that has none.
     *
     * <p>THREE BLOCKED BUCKETS, NOT ONE, and the split is by the phase BLOCKED interrupted.
     * Occupancy is an integral of the PHASE POPULATION array, so the only way to get a split that
     * inherits the partition's guarantees is to make it separate phases - a derived split from
     * per-lineage accumulators would reintroduce exactly the censoring and warm-up cases the
     * population count exists to avoid.
     *
     * <p>They are not the same quantity. A parent in BLOCKED_RUNNING is waiting for a lock it
     * needs to do its own work. A parent in BLOCKED_WAITING_ROOM has already finished its own
     * work and was force-blocked because a SIBLING was denied - what it is really waiting for is
     * its cascade, so that time arguably belongs with "waiting for CUs" rather than with lock
     * contention. Emitting both lets the figure decide without another engine change.
     *
     * <p>BLOCKED_OTHER covers blocking from ADMISSION_WAIT, PENDING_QUEUE, or no open phase. It
     * is expected to be small, and it EXISTS RATHER THAN BEING FOLDED INTO BLOCKED_RUNNING so
     * that "small" is a reading rather than an assumption - folding it in would make a
     * blocked-at-admission population indistinguishable from a blocked-mid-execution one - the
     * same trap as a counter whose zero cannot be told from its absence.
     */
    public enum LineagePhase { PENDING_QUEUE, BACKOFF, ADMISSION_WAIT, RUNNING, WAITING_ROOM,
        BLOCKED_RUNNING, BLOCKED_WAITING_ROOM, BLOCKED_OTHER }

    /** True for the three BLOCKED_* constants, so callers need not enumerate them. */
    public static boolean isBlockedPhase(LineagePhase p) {
        return p == LineagePhase.BLOCKED_RUNNING
                || p == LineagePhase.BLOCKED_WAITING_ROOM
                || p == LineagePhase.BLOCKED_OTHER;
    }

    private long cumulativePendingQueue = 0;
    private long cumulativeBackoff = 0;
    private long cumulativeAdmissionWait = 0;
    private long cumulativeRunning = 0;
    private long cumulativeWaitingRoom = 0;
    private long cumulativeBlocked = 0;
    /** RUNNING time that belongs to attempts which were ABORTED - i.e. wasted work, the
     * category both Abyss (VLDB '14 Fig 6) and Deneva (VLDB '17 Fig 8) report as its own bar.
     *
     * <p>Snapshotted rather than separately accumulated, which is why it needs no new phase and
     * cannot drift out of step with RUNNING: at the moment of a restart, every tick the lineage
     * has spent RUNNING so far belongs to an attempt that did not commit, so the carried-forward
     * total at that moment is the running wasted so far. Re-snapshotting on every restart leaves
     * this holding the total at the LAST restart, and the successful attempt's own running is
     * then {@code cumulativeRunning - cumulativeRunningWasted} - a split of an existing bucket,
     * not a sixth interval competing with the other five.
     *
     * <p>Zero for a lineage that never restarted, which is a genuine zero rather than an unwired
     * one: it is set only from {@link #copyLineageTimeAccountingFrom}, which only a restart
     * calls. */
    private long cumulativeRunningWasted = 0;

    /** TIME-WEIGHTED STATE COUNTS - how many transactions are in each phase RIGHT NOW.
     *
     * <p>This is the textbook discrete-event measurement of "time spent in each state", and it
     * replaces a per-lineage accounting that kept generating edge cases. The per-lineage route
     * needed censoring rules for transactions still running at the end, warm-up rules for those
     * born during the transient, and clipping for those spanning the boundary; two successive
     * estimators built on it failed the Little's-law gate in opposite directions (completed-only
     * read 9.4x LOW, in-flight-unfiltered would read high).
     *
     * <p>Integrating a population count has none of those cases. A transaction is in exactly one
     * phase at every instant, so {@code occupancy[P] = integral of count[P] dt} over the
     * measurement window is a true partition of population-time: nothing is censored (a
     * still-running transaction is simply in some phase at every step), warm-up is just where
     * integration starts, and there are no partial lineages to clip. Dividing by completions then
     * gives per-transaction time in P, satisfying Little's law BY CONSTRUCTION rather than as a
     * property to be checked and patched.
     *
     * <p>Maintained here rather than in SimulationMetrics because {@code markPhase} is the single
     * place every transition passes through - the same reason {@code closeCurrentPhase} already
     * calls a process-global static for backoff intervals. Reset per run via
     * {@link #resetMetrics()}. */
    public static int[] phasePopulation() {
        return TransactionAggregateMetrics.phasePopulation();
    }

    /** The phase BLOCKED interrupted, for the unblock site to restore. Null if none was open. */
    public LineagePhase phaseBeforeBlock() {
        return phaseBeforeBlock;
    }

    private LineagePhase currentPhase = null;
    private long currentPhaseStart = -1;

    /** The phase that was open when BLOCKED was entered, so unblocking can RESTORE it.
     *
     * <p>Without this the unblock site has to name a phase, and naming RUNNING is wrong: a
     * parent blocks from WAITING_ROOM as readily as from RUNNING - it holds its locks while its
     * cascade runs, and that is exactly when a sibling's denial force-blocks it. Measured on the
     * f=0.80 / theta=0.5 / 24-core cell, seed -573968361408801225: hardcoding RUNNING moved
     * 111,486 ticks out of cascade_wait, of which only 66,250 were genuinely blocked and 45,236
     * were waiting-room time that came back as RUNNING. The lifetime still closed to the tick and
     * both gates still read 0.999993, because a partition stays a partition however you misfile
     * its intervals - which is precisely why this needed a before/after diff on one seed to catch
     * rather than a gate.
     *
     * <p>Null when no BLOCKED interval is open, and null is also the correct answer for a
     * transaction blocked before any phase was ever marked. */
    private LineagePhase phaseBeforeBlock = null;

    private void closeCurrentPhase(long currentTime) {
        if (currentPhase == null || currentPhaseStart < 0) {
            return;
        }
        // Leaving this phase: drop out of its live population. Set currentPhase = null below so
        // a second close (markPhase then finalize, or copyLineageTimeAccountingFrom on a
        // transaction that is then discarded) cannot decrement the same membership twice.
        TransactionAggregateMetrics.exitPhase(currentPhase.ordinal());
        long elapsed = Math.max(0, currentTime - currentPhaseStart);
        switch (currentPhase) {
            case PENDING_QUEUE -> cumulativePendingQueue += elapsed;
            case BACKOFF -> {
                cumulativeBackoff += elapsed;
                // Observed HERE, while the object that waited the interval still owns it. Reading
                // per-attempt backoff at completion or at abort instead is structurally impossible
                // on a cascading cell: copyLineageTimeAccountingFrom carries the LINEAGE total
                // forward onto each successor, and the member that completes a group is generally
                // not the member that waited - so both hooks read zero while the lineage total was
                // simultaneously ~6.05bn. See TransactionAggregateMetrics.recordBackoffInterval.
                TransactionAggregateMetrics.recordBackoffInterval(elapsed, lockRetryCount);
            }
            case ADMISSION_WAIT -> cumulativeAdmissionWait += elapsed;
            case RUNNING -> cumulativeRunning += elapsed;
            case WAITING_ROOM -> cumulativeWaitingRoom += elapsed;
            // One accumulator for all three: these per-lineage totals are a secondary diagnostic
            // (the phase OCCUPANCY integral is the estimator every figure reads), and splitting
            // them here would add three fields that nothing consumes.
            case BLOCKED_RUNNING, BLOCKED_WAITING_ROOM, BLOCKED_OTHER ->
                    cumulativeBlocked += elapsed;
        }
        currentPhase = null;
    }

    /** Close the currently-open phase interval and open newPhase. Idempotent-safe to call with
     * the same phase twice in a row at the same currentTime (net zero elapsed) - used for the
     * RACE_RETRY case, which leaves a transaction logically back in PENDING_QUEUE. */
    /** Open a BLOCKED interval, choosing the bucket by what it interrupts.
     *
     * <p>Idempotent: a member blocked on one lock and then force-blocked on another stays in its
     * ORIGINAL bucket, so the interrupted phase is recorded once and BLOCKED never becomes its
     * own predecessor. */
    public void markBlocked(long currentTime) {
        if (isBlockedPhase(currentPhase)) {
            return;
        }
        phaseBeforeBlock = currentPhase;
        LineagePhase bucket = switch (currentPhase == null ? LineagePhase.PENDING_QUEUE : currentPhase) {
            case RUNNING -> LineagePhase.BLOCKED_RUNNING;
            case WAITING_ROOM -> LineagePhase.BLOCKED_WAITING_ROOM;
            default -> LineagePhase.BLOCKED_OTHER;
        };
        markPhase(bucket, currentTime);
    }

    /** Close a BLOCKED interval, RESTORING the phase it interrupted.
     *
     * <p>A NO-OP unless a BLOCKED interval is actually open, and that guard is the point of this
     * method existing. The unblock site drains {@code BLOCKED_TXN}, and not every route into that
     * queue marks a phase - the bookkeeper stuck-admission path does not, and neither do the
     * Wait-Die / Wound-Wait policies, which this campaign does not use but the engine still has.
     * Restoring unconditionally would reopen {@code phaseBeforeBlock} for a transaction that
     * never blocked, which after a restart could be a phase from a PREVIOUS attempt. The gates
     * cannot see that - misfiled intervals still partition - so the invariant is enforced here,
     * at the one place that can check it, rather than trusted at each call site. */
    public void markUnblocked(long currentTime) {
        if (!isBlockedPhase(currentPhase)) {
            return;
        }
        LineagePhase resume = phaseBeforeBlock != null ? phaseBeforeBlock : LineagePhase.RUNNING;
        phaseBeforeBlock = null;
        markPhase(resume, currentTime);
    }

    public void markPhase(LineagePhase newPhase, long currentTime) {
        closeCurrentPhase(currentTime);
        currentPhase = newPhase;
        TransactionAggregateMetrics.enterPhase(newPhase.ordinal());
        currentPhaseStart = currentTime;
    }

    /** Close out whatever phase is open, without opening a new one - call exactly once, at the
     * lineage's terminal observation point (completion or run-end censoring), before reading
     * the four accumulators for export. */
    public void finalizeLineageTimeAccounting(long currentTime) {
        closeCurrentPhase(currentTime);
        currentPhase = null;
        currentPhaseStart = -1;
    }

    /** Restart-time carry-forward: closes the OLD transaction's open interval into its own
     * totals, then copies those now-final totals onto this (new) transaction. Call before this
     * transaction's own markPhase() so the new phase doesn't get folded into the copied total. */
    public void copyLineageTimeAccountingFrom(Transaction original, long currentTime) {
        original.closeCurrentPhase(currentTime);
        this.cumulativePendingQueue = original.cumulativePendingQueue;
        this.cumulativeBackoff = original.cumulativeBackoff;
        this.cumulativeAdmissionWait = original.cumulativeAdmissionWait;
        this.cumulativeRunning = original.cumulativeRunning;
        this.cumulativeWaitingRoom = original.cumulativeWaitingRoom;
        this.cumulativeBlocked = original.cumulativeBlocked;
        // phaseBeforeBlock is DELIBERATELY NOT COPIED, though it sits beside fields that are.
        // The cumulative totals are lineage-level and must carry forward; this field is
        // not a total, it is a pointer into the PREVIOUS attempt's open interval. A transaction
        // that aborts while blocked hands its successor a stale "resume WAITING_ROOM", and the
        // successor - which starts in BACKOFF and has never blocked - would reopen that phase the
        // first time anything drained it from BLOCKED_TXN. Every gate still reads 1.000 through
        // that, because the intervals still partition; only the attribution is wrong.
        //
        // markUnblocked's own guard makes this belt-and-braces rather than the sole defence, and
        // both are kept: the guard stops a bad restore, this stops a stale value existing at all.
        // Read AFTER closeCurrentPhase above, so an attempt aborted while RUNNING has its final
        // open interval folded in before the snapshot - otherwise the last attempt's execution
        // would be counted as useful work on the successor. Every tick of RUNNING accumulated up
        // to this restart belongs to an attempt that did not commit.
        this.cumulativeRunningWasted = original.cumulativeRunning;
    }

    public long getCumulativePendingQueue() {
        return cumulativePendingQueue;
    }

    public long getCumulativeBackoff() {
        return cumulativeBackoff;
    }

    public long getCumulativeAdmissionWait() {
        return cumulativeAdmissionWait;
    }

    public long getCumulativeWaitingRoom() { return cumulativeWaitingRoom; }

    public long getCumulativeRunning() {
        return cumulativeRunning;
    }

    /** RUNNING time spent by attempts that were aborted. Always <= getCumulativeRunning();
     * the difference is the committing attempt's own execution. */
    public long getCumulativeRunningWasted() {
        return cumulativeRunningWasted;
    }

    private static final int UNKNOWN_TERMINAL_ID = -1;
    private final int sourceTerminalId;

    public Transaction(String id, long timestamp, List<Resource> resources, TransactionGroup group) {
        this(id, timestamp, resources, group, UNKNOWN_TERMINAL_ID);
    }

    public Transaction(String id, long timestamp, List<Resource> resources, TransactionGroup group,
            int sourceTerminalId) {
        this(id, timestamp, resources, group, sourceTerminalId, Collections.emptyList(), null);
    }

    public Transaction(String id, long timestamp, List<Resource> resources, TransactionGroup group,
            int sourceTerminalId, List<Operation> operations, String programName) {
        this.setId(id);
        this.timestamp = timestamp;
        this.setResources(resources);
        this.group = group;
        this.sourceTerminalId = sourceTerminalId;
        this.operations = operations != null ? operations : Collections.emptyList();
        this.programName = programName;
        // CRITICAL: NO initializeState() call here
    }

    // Backward-compatible constructor without sourceTerminalId
    public Transaction(String id, List<Resource> resources, TransactionGroup group) {
        this(id, 0, resources, group, UNKNOWN_TERMINAL_ID);
    }

    // Backward-compatible constructor without registry group (defaults to null)
    public Transaction(String id, List<Resource> resources) {
        this(id, 0, resources, null, UNKNOWN_TERMINAL_ID);
    }

    /**
     * Initialize transaction state by firing appropriate creation event.
     * MUST be called after construction to move out of UNINITIALIZED state.
     * 
     * FINAL: Cannot be overridden - ensures one-shot guard always executes.
     * Subclasses customize behavior via creationEvent() hook.
     */
    public final void initializeState() {
        // IDEMPOTENT CHECK: Only allowed from UNINITIALIZED
        if (this.fsm.getState() != TransactionState.UNINITIALIZED) {
            throw new IllegalStateException(
                    "Transaction " + getId() + " already initialized (state=" +
                            this.fsm.getState() + "). initializeState() can only be called once.");
        }
        // Reset completion timestamp for reused transactions
        this.completionTime = 0;
        // Fire creation event (subclasses override creationEvent() to customize)
        this.fsm.fire(creationEvent());
    }

    /**
     * Hook for subclasses to specify which creation event to fire.
     * Default: CREATED event for parent transactions.
     */
    protected TransactionEvent creationEvent() {
        return TransactionEvent.CREATED;
    }

    /**
     * Whether this transaction counts toward system transaction limits.
     * Bookkeepers don't count (part of parent's atomic unit).
     */
    protected boolean countsTowardLimit() {
        return true; // Default: parent transactions count
    }

    public List<Model> getAffectedModels() {
        if (resources == null) return List.of();   // defensive - many non-CPR test fixtures pass null resources
        return resources.stream().map(Resource::model).distinct().toList();
    }

    public void execute() {
        /* ... */ }

    public void onComplete() {
        // Calculate total time strictly for Useful Work tracking
        long totalSimulatedTime = getTotalSimulatedTime();
        // Deadline is set to 5000ms
        boolean missedDeadline = (completionTime - timestamp) > 5000;
        TransactionAggregateMetrics.recordCompletion(metrics, totalSimulatedTime, missedDeadline);
    }

    public void onAbort() {
        // Push the accumulated time to Wasted Work
        long wastedTime = getTotalSimulatedTime();
        TransactionAggregateMetrics.recordAbort(wastedTime);
    }

    public static long getTotalSimulatedIO() {
        return TransactionAggregateMetrics.getTotalSimulatedIO();
    }

    public static long getTotalSimulatedCPU() {
        return TransactionAggregateMetrics.getTotalSimulatedCPU();
    }

    public static long getCompletedTransactions() {
        return TransactionAggregateMetrics.getCompletedTransactions();
    }

    public static void resetMetrics() {
        TransactionAggregateMetrics.reset();
    }

    public String getId() {
        return id;
    }

    /**
     * Get the set of lock IDs required for this transaction.
     * 
     * @param granularityMapper The mapper to convert resources to lock IDs
     * @return Set of unique lock IDs required
     */
    public Set<String> getLockIds(LockGranularityMapper granularityMapper) {
        if (granularityMapper == null) {
            return Collections.emptySet();
        }
        return granularityMapper.collectLockIds(resources);
    }

    public void setId(String id) {
        this.id = id;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public List<Resource> getResources() {
        return resources;
    }

    public void setResources(List<Resource> resources) {
        this.resources = resources;
    }

    // --- operation cursor (S2PL only; inert unless a batch-based acquisition engages it) ---
    private int operationCursor = 0;
    private int currentBatchSize = 0;
    private boolean cursorEngaged = false;
    private boolean batchPending = false;
    private int executedOperationCount = 0;

    /**
     * Operations this transaction has actually executed, accumulated one batch at a time as each
     * batch clears its CPU phase. Distinct from {@code getResources().size()}, which is what the
     * transaction DECLARES it will touch - the two diverge exactly when execution goes wrong, which
     * is when a gate is needed.
     */
    public void recordBatchExecuted(int operationCount) {
        executedOperationCount += Math.max(0, operationCount);
    }

    public int getExecutedOperationCount() {
        return executedOperationCount;
    }

    /**
     * More operations left to acquire locks for.
     *
     * <p>False until a batch acquisition actually engages the cursor, which only
     * {@code S2PLTransactionLockCoordinator} does and only when {@code operationBatchSize > 0}. That
     * is what keeps the whole interleaving path inert at the default: C2PL and {@code b = 0} take
     * the legacy whole-footprint call, never touch the cursor, and this stays false, so the
     * completion hook never fires.
     */
    public boolean hasMoreOperations() {
        return cursorEngaged && operationCursor < operations.size();
    }

    /** Operations in the batch currently executing - empty until the cursor engages. */
    public List<Operation> currentBatch() {
        if (!cursorEngaged || currentBatchSize <= 0) {
            return List.of();
        }
        int from = operationCursor - currentBatchSize;
        return operations.subList(Math.max(0, from), operationCursor);
    }

    /**
     * Claims the next {@code batchSize} operations and engages the cursor. Exactly one batch is in
     * flight per transaction at a time - one cursor, no intra-transaction parallelism.
     *
     * <p><b>A denied batch is re-served, not skipped.</b> The cursor advances when a batch is
     * claimed, but only {@link #recordBatchAcquired} - which the coordinator calls on a successful
     * grant and only then - clears the pending mark. While a batch is pending this returns that same
     * batch again rather than advancing past it.
     *
     * <p>Without that, interleaving silently dropped work. A denied batch blocks the transaction,
     * and the wake path re-enters acquisition through {@code ACIDStrategy.process} - the same entry
     * point as a first admission - so an unconditional advance abandoned the denied batch and took
     * the next one. Those operations never executed and their locks were never taken, while
     * {@code getResources().size()} still reported a full footprint at completion, so
     * refs-per-completion stayed at exactly k and the loss was invisible to it. Measured at k=25,
     * b=1: 1161 of 3140 completions were short, 930 by one batch, 198 by two, 30 by three, 3 by
     * four - a 1.8% shortfall in service demand, and a transaction committing without ever holding
     * some of its locks.
     *
     * <p>The direction is what makes this dangerous rather than merely wrong: skipped work makes
     * interleaving look FASTER, which is the direction rung 1 hopes to see.
     */
    public List<Operation> takeNextBatch(int batchSize) {
        if (batchPending) {
            return currentBatch();
        }
        int from = operationCursor;
        int to = Math.min(operations.size(), from + Math.max(1, batchSize));
        operationCursor = to;
        currentBatchSize = to - from;
        cursorEngaged = true;
        batchPending = true;
        return operations.subList(from, to);
    }

    /**
     * Operations in the work unit currently being executed - the whole transaction when the cursor
     * is disengaged, otherwise just the batch in flight.
     *
     * <p>This is the IO/CPU cost basis. Per OPERATION, not per deduped resource: two touches of one
     * resource are two units of work, and a repeat touch being free is hard to defend physically.
     * Under {@code RANDOM_OPERATIONS} the two bases coincide exactly - synthesis emits one operation
     * per sampled resource - so the ladder is undisturbed. They diverge only in program mode, where
     * a shape like {@code R x, W x, R y, W y} is 4 operations over 2 resources.
     */
    /**
     * Virtual time at which this transaction's PREVIOUS phase actually finished, and therefore
     * the earliest its next phase may start.
     *
     * <p>PhaseDispatcherGroup gives every pool the full budget starting at the SAME virtualTime
     * ("simulated parallelism") and iterates LOCK -> IO -> CPU. So a transaction whose IO ends at
     * local time t in step s is found by the CPU dispatcher later in that same step and, without
     * this, started at local 0 -- gaining up to one quantum at every FORWARD transition. Measured
     * at io=cpu=1000, q=100: per-transaction cost 1900 instead of 2000.
     *
     * <p>0 means "no constraint" - a freshly admitted transaction has no prior phase.
     */
    private long readyAt = 0L;

    /**
     * Phase time remaining when this transaction was last evicted from a unit mid-phase, or -1.
     *
     * <p>INSTRUMENTATION ONLY in this commit: it is recorded and reported, and nothing reads it
     * to change behaviour. `assign()` calls `preparePhase` unconditionally, which resets the
     * remainder to the full phase duration -- so an evicted member redoes that phase. This
     * records how much is re-charged so the effect can be SIZED before anyone decides whether to
     * fix it. Guarding `preparePhase` on "phase unchanged" would be wrong: `tick()` advances the
     * phase internally, so a normal IO->CPU transition also arrives with the phase already set
     * and a remainder of 0, and such a guard would give every CPU phase zero length.
     */
    private long evictedRemaining = -1L;

    public long getEvictedRemaining() {
        return evictedRemaining;
    }

    public void markEvictedMidPhase(long remaining) {
        this.evictedRemaining = remaining;
    }

    public void clearEvictedMark() {
        this.evictedRemaining = -1L;
    }

    /** Earliest virtual time this transaction's next phase may begin. */
    public long getReadyAt() {
        return readyAt;
    }

    /** Stamped at every phase end with the virtual time that phase actually completed. */
    public void setReadyAt(long t) {
        this.readyAt = t;
    }

    public int getCurrentBatchOperationCount() {
        if (cursorEngaged) {
            return currentBatchSize;
        }
        return operations.isEmpty() ? resources.size() : operations.size();
    }

    /**
     * Distinct resources in the current work unit - the LOCK cost basis, which stays per resource
     * rather than per operation: a repeat touch of a held lock is a no-op and an upgrade is one
     * lock-manager operation, not a fresh acquisition.
     */
    public int getCurrentBatchDistinctResourceCount() {
        if (!cursorEngaged) {
            return resources.size();
        }
        return (int) currentBatch().stream().map(Operation::getResource).distinct().count();
    }

    /** Progress through this transaction's operations when it aborted, as a fraction in [0,1]. */
    public double cursorProgress() {
        return operations.isEmpty() ? 0.0 : (double) operationCursor / operations.size();
    }

    public int getOperationCursor() {
        return operationCursor;
    }

    public int getCurrentBatchSize() {
        return currentBatchSize;
    }

    public List<Operation> getOperations() {
        return operations;
    }

    /**
     * True when this transaction provably performs no write.
     *
     * <p>Derived from the operations, which is the only thing that can answer it. Deliberately
     * NOT carried in the ID as a "-RO" suffix: under {@code RANDOM_OPERATIONS} such a suffix can
     * only be computed before the operations are synthesised, so it would label transactions
     * read-only that go on to write. An ID carries identity, not a derived property.
     *
     * <p>Operation-less transactions report false: read-only means "provably performs no write", and
     * with no operations there is nothing to prove it from. Vacuous truth would be the optimistic
     * direction, which is the wrong way to be wrong about write-ness.
     */
    public boolean isReadOnly() {
        return !operations.isEmpty()
                && operations.stream().noneMatch(op -> op.getType() == OperationType.WRITE);
    }

    public String getProgramName() {
        return programName;
    }

    public boolean isProgramGenerated() {
        return !operations.isEmpty();
    }

    public TransactionState getState() {
        return fsm.getState();
    }

    public int getSourceTerminalId() {
        return sourceTerminalId;
    }

    // Allow external components to transition state (e.g., for ON_RESTRICT)
    public void transitionTo(TransactionEvent event) {
        fsm.fire(event);
    }

    public TransactionGroup getGroup() {
        return this.group;
    }

    public void addSimulatedTime(long time, QueueType type) {
        if (type == QueueType.IO)
            metrics.record(ExecutionPhase.IO, time);
        else
            metrics.record(ExecutionPhase.CPU, time);
    }

    public long getSimulatedIOTime() {
        return metrics.getTime(ExecutionPhase.IO);
    }

    public long getSimulatedCPUTime() {
        return metrics.getTime(ExecutionPhase.CPU);
    }

    public long getTotalSimulatedTime() {
        return getSimulatedIOTime() + getSimulatedCPUTime();
    }

    // Lock time is now automatically tracked in metrics during LOCK phase
    public long getSimulatedLockTime() {
        // Return lock time from metrics (actual simulated time spent in LOCK phase)
        return metrics.getTime(ExecutionPhase.LOCK);
    }

    public long getSimulatedRollbackTime() {
        return metrics.getTime(ExecutionPhase.ROLLBACK);
    }
    // Completion timestamp tracking (for BASE recovery latency)
    public void setCompletionTime(long time) {
        this.completionTime = time;
    }

    public long getCompletionTime() {
        return completionTime;
    }

    public void setSubmissionTime(long time) {
        this.submissionTime = time;
    }

    public long getSubmissionTime() {
        return submissionTime;
    }

    public void setFirstAdmissionTime(long time) {
        this.firstAdmissionTime = time;
    }

    public long getFirstAdmissionTime() {
        return firstAdmissionTime;
    }

    // lock_seconds_waiting_room instrumentation (see ACIDAdmissionController.tryAdmitWithLockCheck,
    // TransactionLifecycleManager.moveToWaitingRoom, and the lock-release call sites).
    /**
     * Appends one acquired batch. Called once per successful acquisition.
     *
     * <p><b>The timestamp is clamped forward to this transaction's own progress.</b> Callers pass
     * the DISPATCH-CYCLE clock ({@code CentralQueueManager.getCurrentTime()}, set once per cycle at
     * {@code AbstractAdmissionController.dispatch}), which carries no within-cycle offset - every
     * transaction acquiring during a cycle gets the identical stamp. The release end of the same
     * measurement does carry one ({@code virtualTime + totalUsed}, set at the CPU->COMPLETE
     * boundary), so an unclamped span mixes a cycle-quantised start with a sub-cycle end.
     *
     * <p>For a FIRST acquisition the cycle clock is right: the transaction has not executed, so
     * admission really does happen at the cycle boundary, and {@code completionTime} is still 0 so
     * this clamp is inert. For a cursor BACK-EDGE acquisition it is wrong and the correct value is
     * already on the object: {@code completionTime} holds the exact instant the PREVIOUS batch
     * finished CPU, which is when this batch is acquired. Taking the max recovers that without
     * threading a new clock through the coordinator, and is monotone by construction.
     *
     * <p>Metrics-only: {@code HeldBatch.acquire()} is read solely by {@code recordLockHold},
     * {@code recordAcquisitionSpan} and the abort-path equivalent, so this cannot alter the
     * simulation. That is what makes a fixed-slice before/after a clean isolation of the error -
     * varying the scheduling quantum instead would change the simulation as well as the stamp, and
     * conflate the two.
     */
    public void recordBatchAcquired(int lockCount, long acquire) {
        heldBatches.add(new HeldBatch(Math.max(0, lockCount), Math.max(acquire, completionTime)));
        // The grant signal for the cursor: until this fires, takeNextBatch re-serves the same batch
        // instead of advancing past a batch that was denied. Inert when the cursor is disengaged.
        batchPending = false;
    }

    public List<HeldBatch> getHeldBatches() {
        return List.copyOf(heldBatches);
    }

    /** When this transaction FIRST held a lock, or -1 if it never has. Callers reading this as an
     * admission timestamp (recordGroupLifetime) want the first batch, not the latest. */
    public long getLockHoldStart() {
        return heldBatches.isEmpty() ? -1L : heldBatches.get(0).acquire();
    }

    /** Total locks held across every batch. */
    public int getLockCountHeld() {
        return heldBatches.stream().mapToInt(HeldBatch::lockCount).sum();
    }

    public void setWaitingRoomEntry(long time) {
        this.waitingRoomEntry = time;
    }

    public long getWaitingRoomEntry() {
        return waitingRoomEntry;
    }

    /** Clears lock-hold tracking after the hold interval has been read and accumulated at
     * release, so a reused Transaction object doesn't double-count on a later cycle. */
    public void clearLockHoldTracking() {
        this.heldBatches.clear();
        this.waitingRoomEntry = -1;
    }

    public void setLockWaitStart(long time) {
        this.lockWaitStart = time;
    }

    public long getLockWaitStart() {
        return lockWaitStart;
    }

    /** Clears lock-wait tracking after the wait interval has been read and recorded at
     * successful acquisition. */
    public void clearLockWaitTracking() {
        this.lockWaitStart = -1;
    }

    // Exponential backoff management
    public int getLockRetryCount() { return lockRetryCount; }
    public void incrementLockRetryCount() { lockRetryCount++; }
    public void resetLockRetryCount() { lockRetryCount = 0; }
    public long getEarliestRetryTime() { return earliestRetryTime; }
    public void setEarliestRetryTime(long time) { earliestRetryTime = time; }

    // S2PL soft-deny backoff (see the field's javadoc above) - distinct from lockRetryCount.
    public int getSoftDenyRetryCount() { return softDenyRetryCount; }
    public void incrementSoftDenyRetryCount() { softDenyRetryCount++; }
    public void resetSoftDenyRetryCount() { softDenyRetryCount = 0; }

    // Companion to softDenyRetryCount above (see readyForBackoffRetry's javadoc) - one-shot,
    // consumed by the single admitReadyTransaction() call that follows a BackoffQueue release.
    public boolean consumeReadyForBackoffRetry() {
        boolean was = readyForBackoffRetry;
        readyForBackoffRetry = false;
        return was;
    }
    public void markReadyForBackoffRetry() { readyForBackoffRetry = true; }

    // TGS static-locking validation: admission-attempt bookkeeping (see the field javadoc above).
    public int getAdmissionAttemptIndex() { return admissionAttemptIndex; }
    public void incrementAdmissionAttemptIndex() { admissionAttemptIndex++; }
    public AdmissionAttemptProvenance getAdmissionAttemptProvenance() { return admissionAttemptProvenance; }
    public void setAdmissionAttemptProvenance(AdmissionAttemptProvenance provenance) {
        this.admissionAttemptProvenance = provenance;
    }
    public long getFirstAdmissionAttemptVirtualTime() { return firstAdmissionAttemptVirtualTime; }
    public void setFirstAdmissionAttemptVirtualTime(long virtualTime) {
        this.firstAdmissionAttemptVirtualTime = virtualTime;
    }
    
    public void reset(IOModel ioModel) {
        // Reset all phase times (clear spent time)
        this.metrics.clear();

        this.completionTime = 0;

        // Reset phase executor
        this.phaseExecutor.reset(ioModel, this);

        // Reset FSM to RUNNING so transaction can be retried
        this.fsm.reset();

        // Reset retry backoff state
        this.lockRetryCount = 0;
        this.earliestRetryTime = 0;
    }

    // === Phase Execution Delegation ===

    public ExecutionPhase getPhase() {
        return phaseExecutor.getPhase();
    }

    public void advancePhase() {
        ExecutionPhase before = phaseExecutor.getPhase();
        phaseExecutor.advancePhase();
        VictimTraceLog.log("PHASE", getId(), "phase " + before + " -> " + phaseExecutor.getPhase());
    }

    public void resetPhase(IOModel ioModel) {
        phaseExecutor.reset(ioModel, this);
    }

    public void preparePhase(ExecutionPhase target, IOModel ioModel) {
        VictimTraceLog.log("PREPARE_PHASE", getId(), "from=" + phaseExecutor.getPhase() + " to=" + target);
        phaseExecutor.preparePhase(target, ioModel, this);
    }

    public long getRemainingPhaseTime() {
        return phaseExecutor.getRemainingPhaseTime();
    }

    public void decreaseRemainingPhaseTime(long amount) {
        phaseExecutor.decreaseRemainingTime(amount);
    }

    public void setPhaseDuration(IOModel ioModel) {
        phaseExecutor.setPhaseDuration(ioModel, this);
    }

    public long tick(long delta, IOModel ioModel) {
        // ONLY tick in active execution states
        if (fsm.getState() != TransactionState.RUNNING &&
            fsm.getState() != TransactionState.BACKGROUND_WORK) {
            VictimTraceLog.log("TICK_SKIPPED_WRONG_STATE", getId(), "fsmState=" + fsm.getState());
            return 0;  // Don't tick UNINITIALIZED, PENDING, BLOCKED, COMPLETED, etc.
        }

        ExecutionPhase currentPhase = phaseExecutor.getPhase();
        if (currentPhase == ExecutionPhase.COMPLETE) {
            VictimTraceLog.log("TICK_SKIPPED_ALREADY_COMPLETE", getId(), "");
            return 0;
        }

        long used = Math.min(delta, phaseExecutor.getRemainingPhaseTime());
        phaseExecutor.decreaseRemainingTime(used);
        metrics.record(currentPhase, used);
        if (VictimTraceLog.isWatched(getId())) {
            VictimTraceLog.log("TICK", getId(), "delta=" + delta + " used=" + used
                    + " remainingAfter=" + phaseExecutor.getRemainingPhaseTime() + " phase=" + currentPhase);
        }

        if (phaseExecutor.isPhaseComplete()) {
            fsm.fire(TransactionEvent.PHASE_COMPLETED);
            ExecutionPhase beforeAdvance = phaseExecutor.getPhase();
            phaseExecutor.advancePhase();
            VictimTraceLog.log("PHASE", getId(), "phase " + beforeAdvance + " -> " + phaseExecutor.getPhase() + " (via tick)");

            if (phaseExecutor.getPhase() == ExecutionPhase.COMPLETE) {
                // DON'T automatically fire COMPLETED event here
                // The executor/lifecycle manager will decide when to fire COMPLETED
                // based on whether the transaction has bookkeepers, etc.
            } else {
                phaseExecutor.setPhaseDuration(ioModel, this);
            }
        }
        return used;
    }
}
