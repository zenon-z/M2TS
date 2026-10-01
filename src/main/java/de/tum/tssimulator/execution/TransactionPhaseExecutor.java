package de.tum.tssimulator.execution;

import de.tum.tssimulator.core.ExecutionPhase;
import de.tum.tssimulator.core.Transaction;
import de.tum.tssimulator.model.IOModel;

/**
 * Manages the execution phase lifecycle of a transaction.
 * 
 * Responsibilities:
 * - Track current phase (LOCK -> IO -> CPU -> COMPLETE)
 * - Manage remaining time in current phase
 * - Advance phases when complete
 * - Calculate phase durations from IO model
 * 
 * Separated from Transaction to follow Single Responsibility Principle.
 */
public class TransactionPhaseExecutor {
    private ExecutionPhase phase = ExecutionPhase.LOCK;
    private long remainingPhaseTime = 0;

    public ExecutionPhase getPhase() {
        return phase;
    }

    public long getRemainingPhaseTime() {
        return remainingPhaseTime;
    }

    /**
     * Advance to the next phase in sequence: LOCK -> IO -> CPU -> COMPLETE
     * or RECONCILIATION -> IO -> CPU -> COMPLETE (for BASE bookkeepers)
     * or ROLLBACK -> COMPLETE (for abort undo work)
     */
    public void advancePhase() {
        switch (phase) {
            case LOCK:
                phase = ExecutionPhase.IO;
                break;
            case RECONCILIATION:
                phase = ExecutionPhase.IO;
                break;
            case IO:
                phase = ExecutionPhase.CPU;
                break;
            case CPU:
                phase = ExecutionPhase.COMPLETE;
                break;
            case ROLLBACK:
                phase = ExecutionPhase.COMPLETE;
                break;
            case COMPLETE:
                // Already complete, do nothing
                break;
        }
    }

    /**
     * Reset to LOCK phase with duration from IO model.
     */
    public void reset(IOModel ioModel, Transaction txn) {
        this.phase = ExecutionPhase.LOCK;
        this.remainingPhaseTime = ioModel.getLockAcquisitionTime(txn);
    }

    /**
     * Prepare a specific phase without advancing through sequence.
     * Used for direct phase initialization.
     */
    public void preparePhase(ExecutionPhase target, IOModel ioModel, Transaction txn) {
        this.phase = target;
        setPhaseDuration(ioModel, txn);
    }

    /**
     * Set duration for current phase based on IO model.
     */
    public void setPhaseDuration(IOModel ioModel, Transaction txn) {
        switch (phase) {
            case LOCK:
                remainingPhaseTime = ioModel.getLockAcquisitionTime(txn);
                break;
            case IO:
                remainingPhaseTime = ioModel.getIOTime(txn);
                break;
            case CPU:
                remainingPhaseTime = ioModel.getCPUTime(txn);
                break;
            case ROLLBACK:
                remainingPhaseTime = ioModel.getRollbackTime(txn);
                break;
            case COMPLETE:
                remainingPhaseTime = 0;
                break;
        }
    }

    /**
     * Decrease remaining time in current phase.
     */
    public void decreaseRemainingTime(long amount) {
        remainingPhaseTime -= amount;
    }

    /**
     * Check if current phase is complete.
     */
    public boolean isPhaseComplete() {
        return remainingPhaseTime <= 0;
    }
}
