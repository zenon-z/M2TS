package de.tum.tssimulator.execution;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Consumer;

import de.tum.tssimulator.bookkeeping.BookkeeperTransaction;
import de.tum.tssimulator.core.ExecutionPhase;
import de.tum.tssimulator.core.Transaction;
import de.tum.tssimulator.core.TransactionGroup;
import de.tum.tssimulator.core.TransactionState;
import de.tum.tssimulator.model.IOModel;
import de.tum.tssimulator.monitoring.LogEvent;
import de.tum.tssimulator.monitoring.SimulationLogger;
import de.tum.tssimulator.queue.QueuePort;
import de.tum.tssimulator.queue.QueueType;
import de.tum.tssimulator.state.DirtyStateTracker;

/**
 * Simulated processor unit for BASE reconciliation work.
 *
 * Executes bookkeeper transactions through IO -> CPU phases with normal computation cost.
 * Key differences from SimulatedProcessorUnit:
 * - Only handles bookkeeper transactions from RECONCILIATION queue
 * - Executes on dedicated reconciler processors (separate from parent capacity)
 * - Consumes the full timestep budget, potentially completing multiple small bookkeepers
 */
public class ReconciliationProcessorUnit {
    private static final Logger LOGGER = LoggerFactory.getLogger(ReconciliationProcessorUnit.class);

    private Transaction current = null;
    private final Consumer<Transaction> onCompleteCallback;
    private int stepCounter = 0;
    private int dequeueCounter = 0;

    public ReconciliationProcessorUnit(Consumer<Transaction> onCompleteCallback) {
        this.onCompleteCallback = onCompleteCallback;
    }

    public void assign(Transaction txn, IOModel ioModel, DirtyStateTracker dirtySetManager, SimulationLogger logger) {
        if (!(txn instanceof BookkeeperTransaction bookkeeper) || !bookkeeper.usesBackgroundWorkLifecycle()) {
            LOGGER.error("Non-BASE-bookkeeper transaction {} in RECONCILIATION queue; dropping transaction", txn.getId());
            logger.logEvent(new LogEvent(LogEvent.Type.PHASE_STARTED, txn.getId(), 0L,
                    "error=wrong-type queue=RECONCILIATION"));
            this.current = null;
            return;
        }

        if (txn.getState() != TransactionState.BACKGROUND_WORK) {
            LOGGER.error("Cannot assign transaction {} in state {} (expected BACKGROUND_WORK); dropping transaction",
                    txn.getId(), txn.getState());
            logger.logEvent(new LogEvent(LogEvent.Type.PHASE_STARTED, txn.getId(), 0L,
                    "error=invalid-state state=" + txn.getState() + " queue=RECONCILIATION"));
            this.current = null;
            return;
        }

        if (txn.getPhase() != ExecutionPhase.RECONCILIATION) {
            LOGGER.error("Transaction {} in wrong phase {} (expected RECONCILIATION); dropping transaction",
                    txn.getId(), txn.getPhase());
            logger.logEvent(new LogEvent(LogEvent.Type.PHASE_STARTED, txn.getId(), 0L,
                    "error=invalid-phase phase=" + txn.getPhase() + " queue=RECONCILIATION"));
            this.current = null;
            return;
        }

        this.current = txn;
        logger.metrics().incrementReconciliationBookkeepers();
        logger.logEvent(new LogEvent(LogEvent.Type.PHASE_STARTED, txn.getId(), 0L,
                "phase=RECONCILIATION"));

        txn.advancePhase();
        txn.setPhaseDuration(ioModel);
        logger.logEvent(new LogEvent(LogEvent.Type.PHASE_STARTED, txn.getId(), 0L,
                "phase=IO (reconciliation)"));
    }

    private boolean isSingleAllowedBoundary(ExecutionPhase before, ExecutionPhase after) {
        if (before == ExecutionPhase.LOCK && after == ExecutionPhase.IO) return true;
        if (before == ExecutionPhase.IO && after == ExecutionPhase.CPU) return true;
        if (before == ExecutionPhase.CPU && after == ExecutionPhase.COMPLETE) return true;
        if (before == ExecutionPhase.RECONCILIATION && after == ExecutionPhase.IO) return true;
        return false;
    }

    public long step(long availableTime, QueuePort queue, IOModel ioModel, DirtyStateTracker dirtySetManager,
            SimulationLogger logger, long virtualTime) {
        stepCounter++;
        long remainingBudget = availableTime;
        long consumedBudget = 0;
        long localTime = virtualTime;

        while (remainingBudget > 0) {
            if (current == null) {
                current = queue.dequeue(QueueType.RECONCILIATION);
                if (current != null) {
                    dequeueCounter++;
                }
                if (current != null) {
                    assign(current, ioModel, dirtySetManager, logger);
                    if (current == null) {
                        continue;
                    }
                } else {
                    break;
                }
            }

            ExecutionPhase phaseBefore = current.getPhase();
            long start = localTime;
            long used = current.tick(remainingBudget, ioModel);
            long completionTime = start + used;
            ExecutionPhase phaseAfter = current.getPhase();

            if (phaseBefore != phaseAfter) {
                if (!isSingleAllowedBoundary(phaseBefore, phaseAfter)) {
                    throw new IllegalStateException(
                            "tick() crossed multiple phase boundaries: " +
                                    phaseBefore + " -> " + phaseAfter +
                                    " (violates single-transition invariant)");
                }

                logger.logEvent(new LogEvent(LogEvent.Type.PHASE_COMPLETED, current.getId(), completionTime,
                        "phase=" + phaseBefore + " duration=" + used));

                if (phaseAfter == ExecutionPhase.COMPLETE) {
                    current.setCompletionTime(completionTime);
                    finishCurrent();
                }
            } else if (used == 0 && current.getRemainingPhaseTime() > 0) {
                LOGGER.warn("Transaction {} stuck during reconciliation: state={}, phase={}, remaining={}",
                        current.getId(), current.getState(), current.getPhase(), current.getRemainingPhaseTime());
                this.current = null;
                break;
            }

            if (used <= 0) {
                break;
            }

            consumedBudget += used;
            remainingBudget -= used;
            localTime += used;
        }

        return consumedBudget;
    }

    private void finishCurrent() {
        onCompleteCallback.accept(current);
        this.current = null;
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
}
