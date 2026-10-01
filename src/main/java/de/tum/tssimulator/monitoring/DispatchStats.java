package de.tum.tssimulator.monitoring;

/**
 * Tracks dispatcher behavior to diagnose stall conditions.
 * Only created when DebugConfig.ENABLE_STALL_TRACKING is true.
 */
public class DispatchStats {
    // Counters for dispatch outcomes
    public int totalDispatchCalls = 0;
    public int dispatchesWithWork = 0;
    public int dispatchesNoWork = 0;
    
    // Reasons for no work (mutually exclusive per call)
    public int noWorkBackoffNotDue = 0;
    public int noWorkWaitingForGroup = 0;
    public int noWorkAdmissionFailed = 0;
    public int noWorkLockDenied = 0;
    public int noWorkCapacityLimit = 0;
    public int noWorkQueueEmpty = 0;
    
    // Work execution outcomes
    public int transactionsAdmitted = 0;
    public int parentsAdmitted = 0;  // Parent transactions only (excluding bookkeepers)
    public int transactionsBlocked = 0;
    public int transactionsBackedOff = 0;
    
    // Admission attempt tracking (for thundering herd diagnosis)
    public int admissionAttemptsTotal = 0;
    
    // Queue state tracking (sampled to reduce overhead)
    public long totalRunnableQueueSize = 0;
    public int minRunnableQueueSize = Integer.MAX_VALUE;
    public int maxRunnableQueueSize = 0;
    public int runnableQueueSamples = 0;
    
    public void recordDispatch(boolean hadWork) {
        totalDispatchCalls++;
        if (hadWork) {
            dispatchesWithWork++;
        } else {
            dispatchesNoWork++;
        }
    }
    
    public void recordNoWorkReason(String reason) {
        switch (reason) {
            case "BACKOFF_NOT_DUE" -> noWorkBackoffNotDue++;
            case "WAITING_FOR_GROUP" -> noWorkWaitingForGroup++;
            case "ADMISSION_FAILED" -> noWorkAdmissionFailed++;
            case "LOCK_DENIED" -> noWorkLockDenied++;
            case "CAPACITY_LIMIT" -> noWorkCapacityLimit++;
            case "QUEUE_EMPTY" -> noWorkQueueEmpty++;
        }
    }
    
    public void recordRunnableQueueSize(int size) {
        totalRunnableQueueSize += size;
        minRunnableQueueSize = Math.min(minRunnableQueueSize, size);
        maxRunnableQueueSize = Math.max(maxRunnableQueueSize, size);
        runnableQueueSamples++;
    }
    
    public double getAvgRunnableQueueSize() {
        return runnableQueueSamples > 0 ? (double) totalRunnableQueueSize / runnableQueueSamples : 0;
    }
    
    public int getMinRunnableQueueSize() {
        return minRunnableQueueSize == Integer.MAX_VALUE ? 0 : minRunnableQueueSize;
    }
    
    public String getSummary() {
        double workPct = 100.0 * dispatchesWithWork / Math.max(1, totalDispatchCalls);
        double noWorkPct = 100.0 * dispatchesNoWork / Math.max(1, totalDispatchCalls);
        
        double attemptsPerAdmission = parentsAdmitted > 0 ? (double) admissionAttemptsTotal / parentsAdmitted : 0;
        return String.format(
            "DispatchStats: total=%d, withWork=%d (%.1f%%), noWork=%d (%.1f%%)%n" +
            "  NoWork reasons: backoffNotDue=%d, waitingForGroup=%d, admissionFailed=%d, lockDenied=%d, capacityLimit=%d, queueEmpty=%d%n" +
            "  Work outcomes: admitted=%d (parents=%d), blocked=%d, backedOff=%d%n" +
            "  Admission attempts: total=%d, per_admission=%.2f%n" +
            "  Runnable queue: avg=%.1f, min=%d, max=%d (samples=%d)",
            totalDispatchCalls, dispatchesWithWork, workPct, dispatchesNoWork, noWorkPct,
            noWorkBackoffNotDue, noWorkWaitingForGroup, noWorkAdmissionFailed, noWorkLockDenied,
            noWorkCapacityLimit, noWorkQueueEmpty,
            transactionsAdmitted, parentsAdmitted, transactionsBlocked, transactionsBackedOff,
            admissionAttemptsTotal, attemptsPerAdmission,
            getAvgRunnableQueueSize(), getMinRunnableQueueSize(), maxRunnableQueueSize, runnableQueueSamples
        );
    }
}
