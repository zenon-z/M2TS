package de.tum.tssimulator.execution;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Consumer;
import java.util.function.IntSupplier;

import de.tum.tssimulator.bookkeeping.BookkeeperTransaction;
import de.tum.tssimulator.core.ExecutionPhase;
import de.tum.tssimulator.core.Transaction;
import de.tum.tssimulator.core.TransactionEvent;
import de.tum.tssimulator.core.TransactionGroup;
import de.tum.tssimulator.core.TransactionState;
import de.tum.tssimulator.core.VictimTraceLog;
import de.tum.tssimulator.model.IOModel;
import de.tum.tssimulator.monitoring.LogEvent;
import de.tum.tssimulator.monitoring.SimulationLogger;
import de.tum.tssimulator.queue.CentralQueueManager;
import de.tum.tssimulator.queue.QueuePort;
import de.tum.tssimulator.queue.QueueType;

public class SimulatedProcessorUnit {
    private static final Logger LOGGER = LoggerFactory.getLogger(SimulatedProcessorUnit.class);

    private Transaction current = null;
    // Last virtualTime this unit was stepped with, so out-of-step callers (eviction)
    // can log a real time instead of 0.
    private long lastVirtualTime = 0L;
    private final QueueType type;
    private QueueType currentType = null;
    private final QueueType priorityType;
    private final IntSupplier priorityCapacitySupplier;
    private final Consumer<Transaction> onCompleteCallback;
    private final Consumer<Transaction> onPriorityCompleteCallback;
    private final SimulationLogger logger;
    // Unit affinity (see CentralQueueManager.pooledOwnQueues' field comment): this unit's index
    // within its pool, used both to write the pin (TransactionGroup.pinUnitIfAbsent(), in
    // assign() below) and to read from it (CentralQueueManager.dequeueOwn(), in dequeueNext()
    // below). -1 for pools that don't participate in affinity (LOCK, ROLLBACK) - kept out of
    // scope for this fix, see LockDispatchParallelismCheck for why LOCK is already known-serial
    // for a single transaction's own request; whether a group's C can still overlap each
    // OTHER'S lock acquisition was flagged but not yet checked.
    private final int unitIndex;

    public SimulatedProcessorUnit(QueueType type,
                                   Consumer<Transaction> onCompleteCallback,
                                   SimulationLogger logger) {
        this(type, null, null, onCompleteCallback, null, logger, -1);
    }

    public SimulatedProcessorUnit(QueueType type,
                                   Consumer<Transaction> onCompleteCallback,
                                   SimulationLogger logger,
                                   int unitIndex) {
        this(type, null, null, onCompleteCallback, null, logger, unitIndex);
    }

    public SimulatedProcessorUnit(QueueType type,
                                  QueueType priorityType,
                                  IntSupplier priorityCapacitySupplier,
                                  Consumer<Transaction> onCompleteCallback,
                                  Consumer<Transaction> onPriorityCompleteCallback,
                                  SimulationLogger logger) {
        this(type, priorityType, priorityCapacitySupplier, onCompleteCallback,
                onPriorityCompleteCallback, logger, -1);
    }

    public SimulatedProcessorUnit(QueueType type,
                                  QueueType priorityType,
                                  IntSupplier priorityCapacitySupplier,
                                  Consumer<Transaction> onCompleteCallback,
                                  Consumer<Transaction> onPriorityCompleteCallback,
                                  SimulationLogger logger,
                                  int unitIndex) {
        this.type = type;
        this.priorityType = priorityType;
        this.priorityCapacitySupplier = priorityCapacitySupplier;
        this.onCompleteCallback = onCompleteCallback;
        this.onPriorityCompleteCallback = onPriorityCompleteCallback;
        this.logger = logger;
        this.unitIndex = unitIndex;
    }

    public void assign(Transaction txn, IOModel ioModel) {
        assign(txn, type, ioModel);
    }

    private void assign(Transaction txn, QueueType assignedType, IOModel ioModel) {
        // GUARD: Final safety net - catch any path that missed initialization
        if (txn.getState() == TransactionState.UNINITIALIZED) {
            throw new IllegalStateException(
                "Cannot execute uninitialized transaction: " + txn.getId() + 
                ". This indicates a critical initialization bug in admission/queue path.");
        }
        
        // Defensive check: ensure transaction is in valid state AND phase for execution
        TransactionState state = txn.getState();
        ExecutionPhase phase = txn.getPhase();
        
        // Allow both RUNNING (normal transactions) and BACKGROUND_WORK (BASE bookkeepers in inline mode)
        // BASE bookkeepers use BACKGROUND_WORK state for their entire lifecycle
        if (state != TransactionState.RUNNING &&
            state != TransactionState.BACKGROUND_WORK) {
            // This should never happen - indicates a bug in queue management
            logger.logEvent(new LogEvent(
                LogEvent.Type.PHASE_STARTED,
                txn.getId(), 
                0L,
                "error=invalid-state state=" + state + " phase=" + assignedType.name()));
            
            throw new IllegalStateException(
                "[UNIT-" + assignedType + "] Cannot assign transaction " + txn.getId() +
                " in state " + state + " (expected RUNNING or BACKGROUND_WORK). " +
                "This indicates a bug in queue management.");
        }
        
        if (phase == ExecutionPhase.COMPLETE) {
            // Transaction already finished all work - should not be in execution queue
            logger.logEvent(new LogEvent(
                LogEvent.Type.PHASE_STARTED,
                txn.getId(), 
                0L,
                "error=invalid-phase phase=COMPLETE queue=" + assignedType.name()));
            
            throw new IllegalStateException(
                "[UNIT-" + assignedType + "] Cannot assign transaction " + txn.getId() +
                " in phase COMPLETE (already finished work). This indicates a bug in queue management.");
        }
        
        // Unit affinity (see CentralQueueManager.pooledOwnQueues' field comment): the first
        // member of this txn's group to be assigned to an IO/CPU unit pins the group to THIS
        // unit - every later member (a CU) gets routed straight to this unit's own queue by
        // CentralQueueManager.enqueue() instead of the shared pool. No-op for LOCK/ROLLBACK
        // (unitIndex is -1 there) and for a group already pinned (pinUnitIfAbsent() is a no-op
        // once set).
        if (unitIndex >= 0 && txn.getGroup() != null) {
            txn.getGroup().pinUnitIfAbsent(assignedType, unitIndex);
            // Take the group's single processor slot. IO/CPU only (unitIndex is -1 for
            // LOCK/ROLLBACK), so a member's LOCK phase may still overlap a sibling's IO/CPU.
            if (assignedType == QueueType.IO || assignedType == QueueType.CPU) {
                // Both invariants assert what groupUnitAffinity=true GUARANTEES, so both are
                // gated on it. Ungated they fired as soon as affinity was switched off - i.e. as
                // soon as a group's members legitimately ran concurrently - which made the flag's
                // off arm unrunnable and turned "does CU work parallelise?" into an assumption
                // rather than a measurement.
                boolean affinity = ioModel.getConfig().groupUnitAffinity;
                txn.getGroup().occupyProcessor(txn, affinity);
                // Measured at the unit, independent of the slot bookkeeping above.
                txn.getGroup().enterProcessorUnit(txn, affinity);
            }
        }

        this.current = txn;
        this.currentType = assignedType;
        // Initialize phase to match this unit and set duration using Transaction API; emit PHASE_STARTED
        ExecutionPhase target;
        if (assignedType == QueueType.LOCK) {
            target = ExecutionPhase.LOCK;
        } else if (assignedType == QueueType.IO) {
            target = ExecutionPhase.IO;
        } else if (assignedType == QueueType.ROLLBACK) {
            target = ExecutionPhase.ROLLBACK;
        } else {
            target = ExecutionPhase.CPU;
        }
        long evicted = txn.getEvictedRemaining();
        txn.preparePhase(target, ioModel);
        if (evicted >= 0) {
            // The re-charge is the PHASE PROGRESS that gets redone: this phase's full duration
            // minus what was left at eviction. Computed AFTER preparePhase, since that is what
            // sets the full duration. Subtracting from getTotalTime() instead - the
            // whole-transaction lock+io+cpu figure - would overstate every event by roughly the
            // other phases' cost.
            logger.metrics().recordEvictionRecharge(txn.getRemainingPhaseTime() - evicted);
            txn.clearEvictedMark();
        }
        logger.logEvent(new LogEvent(LogEvent.Type.PHASE_STARTED, txn.getId(), 0L,
                "phase=" + assignedType.name()));
    }

    /**
     * Check if a phase transition represents a single allowed boundary.
     * Allowed single transitions:
     *   LOCK → IO
     *   IO → CPU
     *   CPU → COMPLETE
     *   RECONCILIATION → IO
     */
    private boolean isSingleAllowedBoundary(ExecutionPhase before, ExecutionPhase after) {
        if (before == ExecutionPhase.LOCK && after == ExecutionPhase.IO) return true;
        if (before == ExecutionPhase.IO && after == ExecutionPhase.CPU) return true;
        if (before == ExecutionPhase.CPU && after == ExecutionPhase.COMPLETE) return true;
        if (before == ExecutionPhase.ROLLBACK && after == ExecutionPhase.COMPLETE) return true;
        if (before == ExecutionPhase.RECONCILIATION && after == ExecutionPhase.IO) return true;
        return false;
    }

    private static final int MAX_TRANSACTIONS_PER_STEP = 10_000;

    /**
     * Clears this unit's slot. It does NOT release the group's processor slot.
     *
     * <p>The slot is held across a member's IO->CPU QUEUE WAIT, during which the member is
     * `current` on no unit at all -- so a unit-scoped release can never fire for it, and one
     * keyed on the member's phase would read a transaction the unit no longer holds. Release is
     * therefore driven by the member's LIFECYCLE (completion, abort/rollback, force-block) and
     * the unit only ever TAKES the slot, in assign().
     */
    private void releaseCurrent() {
        if (current != null && current.getGroup() != null
                && (currentType == QueueType.IO || currentType == QueueType.CPU)) {
            current.getGroup().leaveProcessorUnit();
        }
        this.current = null;
        this.currentType = null;
    }

    public long step(long availableTime, QueuePort queue, IOModel ioModel, long virtualTime) {
        long totalUsed = 0;
        int processedCount = 0;
        this.lastVirtualTime = virtualTime;

        // Keep pulling and ticking transactions through this unit as long as time budget
        // remains - a unit that finishes one transaction in less than its full budget should
        // pick up the next queued transaction rather than sit idle for the rest of the step.
        while (totalUsed < availableTime) {
            if (current == null) {
                current = dequeueNext(queue, virtualTime, virtualTime + availableTime);
                if (current != null) {
                    // Honour the hand-off stamp: a transaction whose previous phase ended at
                    // local offset t in THIS step cannot start before t. Idles the unit for the
                    // interval rather than crediting it as work, which is what made forward
                    // transitions gain a quantum.
                    // A transaction whose previous phase ended at local offset t cannot start
                    // before t. At ALIGNED costs a phase ends exactly at the step boundary, so
                    // readyOffset == availableTime on EVERY forward transition -- this is the
                    // normal path, not a defensive one, and the earlier warn-and-break treated
                    // it as an anomaly (see the correcting note in the plan). Not-ready work is
                    // simply left queued for the next step; nothing is re-enqueued and the log
                    // stays quiet.
                    // dequeueNext only returns work that is ready within this step, so the
                    // only thing left is to advance local time to its ready offset -- the unit
                    // idles until then rather than crediting the interval as work.
                    long readyOffset = current.getReadyAt() - virtualTime;
                    if (readyOffset > totalUsed) {
                        totalUsed = readyOffset;
                    }
                    assign(current, currentType, ioModel);
                } else {
                    break;  // No more work for this unit this step
                }
            }

            if (++processedCount > MAX_TRANSACTIONS_PER_STEP) {
                LOGGER.error("SimulatedProcessorUnit[{}] exceeded {} transactions in a single step; " +
                        "breaking to avoid an infinite loop", type, MAX_TRANSACTIONS_PER_STEP);
                break;
            }

            long remainingBudget = availableTime - totalUsed;

            // Track phase BEFORE tick
            ExecutionPhase phaseBefore = current.getPhase();
            long start = virtualTime + totalUsed;
            long used = current.tick(remainingBudget, ioModel);
            // Busy time attributed to WHAT RAN (currentType: CPU vs ROLLBACK on a CPU unit) and to
            // WHOSE work it is (parent vs CU). Single accumulation point, because this is the only
            // place a unit's busy time is produced. No-op unless the work-conservation audit is on.
            CompositePhaseDispatcher.recordBusyTicks(
                    currentType, current instanceof BookkeeperTransaction, used);
            long end = start + used;  // Local completion time
            ExecutionPhase phaseAfter = current.getPhase();

            // Detect phase transitions
            if (phaseBefore != phaseAfter) {
                // INVARIANT CHECK: at most one phase boundary per tick
                if (!isSingleAllowedBoundary(phaseBefore, phaseAfter)) {
                    throw new IllegalStateException(
                        "tick() crossed multiple phase boundaries: " +
                        phaseBefore + " -> " + phaseAfter +
                        " (violates single-transition invariant)");
                }

                logger.logEvent(new LogEvent(
                    LogEvent.Type.PHASE_COMPLETED,
                    current.getId(), end,
                    "phase=" + phaseBefore + " duration=" + used));

                // ONLY set completion time on final CPU → COMPLETE transition
                if (currentType == QueueType.CPU
                    && phaseBefore == ExecutionPhase.CPU
                    && phaseAfter == ExecutionPhase.COMPLETE) {
                    current.setCompletionTime(end);
                }

                // Time-consistent hand-off: record when this phase ACTUALLY ended, so the
                // next pool -- which may run later in this same dispatch call -- cannot start
                // the next phase at local 0. See Transaction.readyAt.
                current.setReadyAt(end);

                if (shouldFinishCurrent(phaseBefore)) {
                    finishCurrent(queue);
                }
            } else if (used == 0 && current.getRemainingPhaseTime() > 0) {
                // Stuck transaction: tick returns 0 but phase isn't advancing
                logger.logEvent(new LogEvent(
                    LogEvent.Type.WARNING,
                    current.getId(),
                    end,
                    "stuck-transaction unit=" + type +
                    " state=" + current.getState() +
                    " phase=" + current.getPhase() +
                    " remaining=" + current.getRemainingPhaseTime()));

                // Clear the stuck transaction to prevent infinite loop
                releaseCurrent();
            }

            totalUsed += used;

            // No progress this iteration (stuck-transaction path above, or a zero-cost tick with
            // nothing left queued) - stop rather than spin without advancing totalUsed.
            if (used == 0) {
                break;
            }
        }

        return totalUsed;
    }

    private Transaction dequeueNext(QueuePort queue, long virtualTime, long stepEnd) {
        if (priorityType != null
                && !queue.isEmpty(priorityType)
                && canStartPriorityWork()) {
            Transaction txn = queue.dequeue(priorityType);
            if (txn != null) {
                if (txn.getState() == TransactionState.READY) {
                    txn.transitionTo(TransactionEvent.CAPACITY_AVAILABLE);
                }
                currentType = priorityType;
                return txn;
            }
        } else if (priorityType != null && !queue.isEmpty(priorityType) && !canStartPriorityWork()
                && VictimTraceLog.isTracingEnabled()) {
            // Cheap unconditional diagnostic (not per-victim gated - no peek available on
            // QueuePort to see which txn is stuck at the head): fires whenever the priority
            // (ROLLBACK) queue is non-empty but the capacity gate blocks it from being serviced
            // this step. Only prints while a victim trace filter is active at all, reusing
            // isWatched("") as a cheap "is tracing on" check without leaking a public accessor.
            System.err.println("VICTIM-TRACE ROLLBACK_PRIORITY_BLOCKED_BY_CAPACITY queueSize="
                    + queue.size(priorityType) + " unit=" + type);
        }

        // Unit affinity (see CentralQueueManager.pooledOwnQueues' field comment): drain this
        // unit's own pinned work before falling through to the shared pool below, so a group's
        // C land back on the same unit their parent was assigned to. instanceof rather than a
        // QueuePort interface method - CentralQueueManager is the only production implementer,
        // and QueuePort/QueueManager have test-only fakes that would otherwise all need a new
        // abstract method just for this.
        if (unitIndex >= 0 && queue instanceof CentralQueueManager centralQueueManager) {
            Transaction own = centralQueueManager.dequeueOwn(type, unitIndex, virtualTime, stepEnd);
            if (own != null) {
                currentType = type;
                return own;
            }
        }

        // The shared pool has no scan, so readiness is checked at the point of use: a
        // not-ready transaction is put back and this unit takes nothing this step. Unlike the
        // pinned path this can head-of-line block, which is acceptable because a group with no
        // pin for this pool has no sibling ahead of it to be blocked by.
        Transaction txn = (queue instanceof CentralQueueManager cqm)
                ? cqm.dequeueFirstEligible(type, virtualTime, stepEnd)
                : queue.dequeue(type);
        if (txn != null) {
            currentType = type;
        }
        return txn;
    }

    private boolean canStartPriorityWork() {
        return priorityCapacitySupplier == null || priorityCapacitySupplier.getAsInt() > 0;
    }
    
    /**
     * Determine if the current transaction should be finished based on which phase completed.
     */
    private boolean shouldFinishCurrent(ExecutionPhase completedPhase) {
        // LOCK phase complete -> move to IO queue
        if (completedPhase == ExecutionPhase.LOCK && currentType == QueueType.LOCK) {
            return true;
        }
        // IO phase complete -> move to CPU queue
        if (completedPhase == ExecutionPhase.IO && currentType == QueueType.IO) {
            return true;
        }
        // CPU phase complete -> invoke callback
        if (completedPhase == ExecutionPhase.CPU && currentType == QueueType.CPU) {
            return true;
        }
        if (completedPhase == ExecutionPhase.ROLLBACK && currentType == QueueType.ROLLBACK) {
            return true;
        }
        return false;
    }

    private void finishCurrent(QueuePort queue) {
        // Check if transaction was aborted or completed mid-execution
        TransactionState state = current.getState();
        if (state == TransactionState.ABORTED ||
            state == TransactionState.COMPLETED) {
            // Transaction terminated - don't enqueue to next phase
            LOGGER.warn("[UNIT-{}] Transaction {} terminated mid-execution (state={}); dropping without enqueueing",
                    currentType, current.getId(), state);
            releaseCurrent();
            return;
        }
        
        QueueType next;
        if (currentType == QueueType.LOCK) {
            next = QueueType.IO;
        } else if (currentType == QueueType.IO) {
            next = QueueType.CPU;
        } else {
            next = null;
        }

        if (next != null) {
            // Transition to next phase by enqueueing; no direct lifecycle calls here
            queue.enqueue(current, next);
        } else {
            // CPU phase complete - invoke callback
            if (currentType == QueueType.ROLLBACK && onPriorityCompleteCallback != null) {
                onPriorityCompleteCallback.accept(current);
            } else {
                onCompleteCallback.accept(current);
            }
        }

        releaseCurrent();
    }

    public boolean isIdle() {
        return current == null;
    }

    public int getInFlightCount() {
        return current == null ? 0 : 1;
    }
    
    public TransactionGroup getInFlightGroup() {
        return (current != null && current.getGroup() != null) ? current.getGroup() : null;
    }
    
    /**
     * Returns count of non-bookkeeper transactions (1 if current is a regular transaction, 0 otherwise).
     * Bookkeepers don't consume processor slots - they execute as part of their parent transaction.
     */
    public int getNonBookkeeperCount() {
        if (current == null) return 0;
        return (current instanceof BookkeeperTransaction) ? 0 : 1;
    }
    
    /**
     * Forcibly clears the current transaction if it matches the specified transaction.
     * Returns true if the transaction was found and cleared, false otherwise.
     * This is used when a transaction is blocked mid-execution and must be removed from the processor.
     */
    public boolean clearTransaction(Transaction txn) {
        if (current != null && current.getId().equals(txn.getId())) {
            releaseCurrent();
            return true;
        }
        return false;
    }

    /**
     * Non-mutating check: is this transaction currently occupying this unit's `current` slot?
     * OrphanTransactionAudit's building block - a RUNNING transaction must be findable either in
     * some queue (CentralQueueManager.contains()) or here; if neither, it's a dropped reference
     * (see the CLEAR_FROM_PROCESSORS / ABORTING re-entrancy bug this audit was written to catch).
     */
    public boolean isCurrentlyAssigned(Transaction txn) {
        return current != null && current.getId().equals(txn.getId());
    }
    
    /**
     * Clears the current transaction if it belongs to the specified group.
     * Returns true if a transaction was found and cleared, false otherwise.
     * This prevents aborted group members from continuing execution.
     */
    public boolean clearTransactionsByGroup(TransactionGroup group) {
        if (current != null && current.getGroup() != null && current.getGroup().equals(group)) {
            // A distinct event with the REAL virtual time. Logging this as PHASE_COMPLETED at
            // vt=0 made evictions indistinguishable from genuine phase ends at time zero, which
            // would corrupt any per-group m-counting or W>1 invariant check parsed from the log.
            long remaining = current.getRemainingPhaseTime();
            if (remaining > 0) {
                current.markEvictedMidPhase(remaining);
            }
            logger.logEvent(new LogEvent(LogEvent.Type.PHASE_EVICTED,
                current.getId(), lastVirtualTime, "cleared-from-processor group-aborted remaining="
                    + remaining));
            releaseCurrent();
            return true;
        }
        return false;
    }
}
