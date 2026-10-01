package de.tum.tssimulator.execution;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import de.tum.tssimulator.core.Transaction;
import de.tum.tssimulator.core.TransactionGroup;
import de.tum.tssimulator.model.IOModel;
import de.tum.tssimulator.queue.CentralQueueManager;
import de.tum.tssimulator.queue.QueuePort;
import de.tum.tssimulator.queue.QueueType;

public class CompositePhaseDispatcher implements PhaseDispatcher {
    private final List<SimulatedProcessorUnit> units;
    private final QueuePort queue;
    private final IOModel ioModel;
    private final QueueType queueType;

    // Diagnostic only (opt-in via -Dts.diag.dispatchTrace=true): records how many of this
    // dispatcher's units did real work (stepUsed > 0) per dispatch() call, keyed by
    // QueueType.ordinal(). Direct empirical test of whether a single transaction's own
    // resource footprint (e.g. its LOCK phase) ever spreads across more than one processor -
    // IOModel.getLockAcquisitionTime()'s javadoc already documents that it shouldn't
    // ("NOT parallelized across processors... serial bottleneck"), this confirms it at
    // runtime instead of by code reading alone.
    private static final boolean DISPATCH_TRACE_ENABLED = Boolean.getBoolean("ts.diag.dispatchTrace");
    public static final List<int[]> dispatchTrace = DISPATCH_TRACE_ENABLED
            ? Collections.synchronizedList(new ArrayList<>()) : null;

    public CompositePhaseDispatcher(List<SimulatedProcessorUnit> units,
                                    QueuePort queue,
                                    IOModel ioModel,
                                    QueueType queueType) {
        this.units = units;
        this.queue = queue;
        this.ioModel = ioModel;
        this.queueType = queueType;
    }

    /**
     * WORK-CONSERVATION AUDIT (opt-in, {@code -Dts.diag.workConservation=true}).
     *
     * <p>Answers one question directly rather than by code reading: was there ever a dispatch at
     * which a unit of this pool sat idle while work in its queue was READY to run? A
     * work-conserving dispatcher cannot produce that. Anything else - an idle unit beside
     * not-yet-ready work, or beside an empty queue - is correct behaviour and is counted
     * separately so the two cannot be confused.
     *
     * <p>Motivated by a measurement the existing metrics could not explain: at 1 core / f = 0.80
     * the RUNNING residual put 20.3 parents in an admitted phase while {@code unit_utilisation}
     * read 16.6%, i.e. queued work at an idle server. Three separate code-reading arguments said
     * the dispatcher is fine ({@code dequeueFirstEligible} scans the whole queue,
     * {@code groupUnitAffinity} is off on this axis, and blocked transactions are not in these
     * queues at all) - but a code-reading argument is exactly what produced the "CU pinning
     * confirmed" claim that the data later killed, so this measures it instead.
     *
     * <p>Indexed by {@code QueueType.ordinal()}. Recorded per POOL, because the pools are sized
     * differently: IO and CPU scale with the core count while LOCK is sized at
     * {@code maxConcurrent} (192), so "the units were idle" means something different in each and
     * a combined figure would hide the LOCK pool entirely.
     */
    private static final boolean WC_AUDIT = Boolean.getBoolean("ts.diag.workConservation");

    /** Per QueueType.ordinal(): {dispatches, unitsTotal, sumBusyUnits, dispatchesWithIdleUnit,
     *  dispatchesIdleWithReadyWork, sumQueueDepth, sumReadyDepth, sumIdleUnitsWhenReadyWork}. */
    public static final long[][] wcStats =
            WC_AUDIT ? new long[QueueType.values().length][8] : null;

    /**
     * BUSY TIME BY WHAT WAS EXECUTED, per {@code [QueueType.ordinal()][0 = parent, 1 = CU]}.
     *
     * <p>{@code wcStats} answers "was a unit idle beside ready work" and its {@code mean_busy} is a
     * count of units, not time - so it cannot say what a saturated pool is saturated WITH. At 1
     * core / f = 0.80 the CPU unit measured 100% busy with 1655 ready behind it, which leaves the
     * deciding question open: how much of that is transaction CPU work, how much is CU work, and
     * how much is ROLLBACK. Rollback is the PRIORITY queue on CPU units, so an
     * abort -> rollback -> CPU-saturation loop would show up here as a large ROLLBACK share and
     * nowhere else.
     *
     * <p>Keyed on the unit's {@code currentType} - what it is RUNNING - not on the pool it belongs
     * to, which is the distinction that separates CPU work from ROLLBACK work on the same unit.
     *
     * <p>Whole-run, not windowed: it pairs with {@code groupsCompletedTotal} rather than with the
     * post-warm-up {@code completionsInWindow}, and the audit divides by the matching one.
     */
    public static final long[][] wcBusyTicks =
            WC_AUDIT ? new long[QueueType.values().length][2] : null;

    /** No-op unless the audit is armed; callers need no guard. */
    public static void recordBusyTicks(QueueType executed, boolean isCu, long ticks) {
        if (wcBusyTicks == null || executed == null || ticks <= 0) {
            return;
        }
        wcBusyTicks[executed.ordinal()][isCu ? 1 : 0] += ticks;
    }

    public static void resetWorkConservationStats() {
        if (wcStats != null) {
            for (long[] row : wcStats) {
                Arrays.fill(row, 0L);
            }
        }
        if (wcBusyTicks != null) {
            for (long[] row : wcBusyTicks) {
                Arrays.fill(row, 0L);
            }
        }
    }

    @Override
    public long dispatch(long timeBudget, long virtualTime) {
        // Depths sampled BEFORE the units run, which is the only instant at which "was work
        // available to this dispatch" is answerable - after the loop the queue has already been
        // drained by whatever did run.
        int depthBefore = 0;
        int readyBefore = 0;
        if (WC_AUDIT) {
            depthBefore = queue.size(queueType);
            readyBefore = (queue instanceof CentralQueueManager cqm)
                    ? cqm.countReadyWithin(queueType, virtualTime + timeBudget)
                    : depthBefore;
        }
        // All processors execute in parallel, each getting the FULL timeBudget. Sharing one
        // budget sequentially would let only the first unit execute.
        long maxUsed = 0;
        int busyCount = 0;
        for (SimulatedProcessorUnit unit : units) {
            // Each processor gets the FULL time budget (they run in parallel in simulated time)
            long stepUsed = unit.step(timeBudget, queue, ioModel, virtualTime);
            if (stepUsed > 0) {
                busyCount++;
            }
            // Track the maximum time used by any processor (this is how much simulated time advances)
            maxUsed = Math.max(maxUsed, stepUsed);
        }
        if (DISPATCH_TRACE_ENABLED && busyCount > 0) {
            dispatchTrace.add(new int[] {queueType.ordinal(), busyCount});
        }
        if (WC_AUDIT) {
            long[] s = wcStats[queueType.ordinal()];
            int idle = units.size() - busyCount;
            s[0]++;
            s[1] = units.size();            // constant per pool; assigned rather than summed
            s[2] += busyCount;
            s[5] += depthBefore;
            s[6] += readyBefore;
            if (idle > 0) {
                s[3]++;
                // THE VIOLATION COUNTER. busyCount counts units that USED time this dispatch, so
                // a unit still mid-transaction from a previous step and legitimately occupied
                // could read idle here and inflate this. That direction is safe for the
                // conclusion being drawn: it can only make a work-conserving dispatcher look
                // worse, so a ZERO here is strong evidence and a nonzero needs the per-unit
                // follow-up rather than being taken as proof of a bug.
                if (readyBefore > 0) {
                    s[4]++;
                    s[7] += idle;
                }
            }
        }
        // Return the maximum time used (simulated time advances by the slowest processor)
        return maxUsed;
    }

    public int getInFlightCount() {
        return units.stream().mapToInt(SimulatedProcessorUnit::getInFlightCount).sum();
    }
    
    @Override
    public Set<TransactionGroup> getInFlightGroups() {
        return units.stream()
                .map(SimulatedProcessorUnit::getInFlightGroup)
                .filter(group -> group != null)
                .collect(Collectors.toSet());
    }
    
    /**
     * Returns count of non-bookkeeper transactions currently being processed.
     * Bookkeepers don't count toward processor limit.
     */
    public int getNonBookkeeperCount() {
        return units.stream().mapToInt(SimulatedProcessorUnit::getNonBookkeeperCount).sum();
    }
    
    /**
     * Clears a specific transaction from all processor units if it's currently being executed.
     * Returns true if the transaction was found and cleared, false otherwise.
     * Used when a transaction must be forcibly stopped mid-execution (e.g., when blocked).
     */
    public boolean clearTransactionFromProcessors(Transaction txn) {
        boolean cleared = false;
        for (SimulatedProcessorUnit unit : units) {
            if (unit.clearTransaction(txn)) {
                cleared = true;
            }
        }
        return cleared;
    }

    /** Non-mutating: is this transaction `current` in any unit of this dispatcher? */
    public boolean isTransactionAssigned(Transaction txn) {
        for (SimulatedProcessorUnit unit : units) {
            if (unit.isCurrentlyAssigned(txn)) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * Clears all transactions belonging to the specified group from processor units.
     * Returns the count of transactions cleared.
     * Used when a transaction group is aborted to prevent continued execution.
     */
    public int clearTransactionsByGroup(TransactionGroup group) {
        int cleared = 0;
        for (SimulatedProcessorUnit unit : units) {
            if (unit.clearTransactionsByGroup(group)) {
                cleared++;
            }
        }
        return cleared;
    }
}
