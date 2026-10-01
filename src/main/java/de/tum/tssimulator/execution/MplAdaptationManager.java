package de.tum.tssimulator.execution;

import de.tum.tssimulator.execution.AbstractAdmissionController;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Manages adaptive Multiprogramming Level (MPL) by monitoring system metrics
 * and dynamically adjusting the processor capacity in the AdmissionController.
 */
public class MplAdaptationManager {
    public enum AdaptationFunction {
        NONE,
        BINARY, // High/Low toggle
        LINEAR, // Additive Increase, Additive Decrease (AIMD but additive)
        NON_LINEAR, // Additive Increase, Multiplicative Decrease (AIMD)
        
        // Advanced Math Models
        QUADRATIC,
        EXPONENTIAL,
        CUBIC,
        LOGARITHMIC,
        SIGMOID,
        MIAD // Multiplicative Increase, Additive Decrease
    }

    private static final MplAdaptationManager INSTANCE = new MplAdaptationManager();

    private AbstractAdmissionController admissionController;
    private AdaptationFunction currentFunction = AdaptationFunction.NONE;

    // MPL Bounds
    private int maxMpl = 60;
    private int minMpl = 10;
    private int currentMpl = 60;

    // BINARY mode specific
    private int binaryHighMpl = 60;
    private int binaryLowMpl = 30;

    // LINEAR mode specific
    private int linearStep = 5;

    // Metrics (Windowed)
    private final AtomicLong committedTxns = new AtomicLong(0);
    private final AtomicLong abortedTxns = new AtomicLong(0);
    private final AtomicLong currentLocksHeld = new AtomicLong(0);

    // Thresholds
    private double currentAlphaThreshold = 0.226;
    private int totalSystemLocks = 5000;

    private MplAdaptationManager() {
    }

    public static MplAdaptationManager getInstance() {
        return INSTANCE;
    }

    public void initialize(AbstractAdmissionController controller, AdaptationFunction function, int maxCapacity) {
        this.admissionController = controller;
        this.currentFunction = function;
        this.maxMpl = maxCapacity;
        this.currentMpl = maxCapacity;

        this.binaryHighMpl = maxCapacity;
        this.binaryLowMpl = Math.max(minMpl, maxCapacity / 2);

        resetMetrics();
        applyMpl(this.currentMpl);
    }

    public void setMplBounds(int min, int max) {
        this.minMpl = Math.max(1, min);
        this.maxMpl = Math.max(this.minMpl, max);
    }

    public void setTotalSystemLocks(int totalLocks) {
        this.totalSystemLocks = totalLocks;
    }

    public void setAlphaThreshold(double threshold) {
        this.currentAlphaThreshold = threshold;
    }

    public void resetMetrics() {
        committedTxns.set(0);
        abortedTxns.set(0);
        currentLocksHeld.set(0);
        if (admissionController != null) {
            applyMpl(maxMpl);
        }
    }

    public int getCurrentMpl() {
        return currentMpl;
    }

    // --- Metric Collection ---
    // Can be called by existing monitoring hooks or the AdmissionController
    public void recordCommit() {
        committedTxns.incrementAndGet();
    }

    public void recordAbort() {
        abortedTxns.incrementAndGet();
    }

    public void recordLockAcquired() {
        currentLocksHeld.incrementAndGet();
    }

    public void recordLockReleased() {
        currentLocksHeld.decrementAndGet();
    }

    // --- Adaptation Logic (Called periodically) ---
    public void updateMpl() {
        if (currentFunction == AdaptationFunction.NONE || admissionController == null) {
            return;
        }

        long committed = committedTxns.get();
        long aborted = abortedTxns.get();
        long totalOps = committed + aborted;

        long locksHeld = Math.max(0, currentLocksHeld.get());
        double alpha = (double) locksHeld / totalSystemLocks;

        boolean isThrashing = alpha > currentAlphaThreshold;

        if (totalOps >= 10) {
            isThrashing = isThrashing || ((double) aborted / totalOps > 0.3);

            // We used the ops window, so we reset for the next window
            committedTxns.set(0);
            abortedTxns.set(0);
        } else {
            // Keep accumulating operations over time until we have enough to calculate a
            // meaningful abort rate.
            // But we still evaluate alpha (isThrashing is true if alpha > threshold)
        }

        int newMpl = currentMpl;

        if (isThrashing) {
            newMpl = calculateDecrease(currentMpl);
        } else {
            newMpl = calculateIncrease(currentMpl);
        }

        if (newMpl != currentMpl) {
            applyMpl(newMpl);
        }
    }

    private int calculateDecrease(int current) {
        switch (currentFunction) {
            case BINARY:
                return binaryLowMpl;
            case LINEAR:
                return Math.max(minMpl, current - linearStep);
            case NON_LINEAR:
                // Multiplicative Decrease (halve the MPL, but don't drop below min)
                return Math.max(minMpl, current / 2);
            case EXPONENTIAL:
                return Math.max(minMpl, current / 2); // Same aggressive cut to save system
            case QUADRATIC:
                return Math.max(minMpl, current - Math.max(1, (int) Math.round(Math.sqrt(current))));
            case CUBIC:
                return Math.max(minMpl, current - Math.max(1, (int) Math.round(Math.pow(current, 2.0 / 3.0))));
            case LOGARITHMIC:
                return Math.max(minMpl, current - Math.max(1, (int) Math.round(10.0 / Math.log(current + 2))));
            case SIGMOID:
                double range = maxMpl - minMpl;
                double x = (range <= 0) ? 0.5 : (double) (current - minMpl) / range;
                int sigStep = Math.max(1, (int) Math.round(15.0 * x * (1.0 - x)));
                return Math.max(minMpl, current - sigStep);
            case MIAD:
                // Additive Decrease
                return Math.max(minMpl, current - linearStep);
            default:
                return current;
        }
    }

    private int calculateIncrease(int current) {
        switch (currentFunction) {
            case BINARY:
                return binaryHighMpl;
            case LINEAR:
            case NON_LINEAR:
                // Additive Increase for both (AIMD)
                return Math.min(maxMpl, current + linearStep);
            case EXPONENTIAL:
                return Math.min(maxMpl, Math.max(current + 1, (int) Math.round(current * 1.1)));
            case QUADRATIC:
                return Math.min(maxMpl, current + Math.max(1, (int) Math.round(Math.sqrt(current))));
            case CUBIC:
                return Math.min(maxMpl, current + Math.max(1, (int) Math.round(Math.pow(current, 2.0 / 3.0))));
            case LOGARITHMIC:
                return Math.min(maxMpl, current + Math.max(1, (int) Math.round(10.0 / Math.log(current + 2))));
            case SIGMOID:
                double range = maxMpl - minMpl;
                double x = (range <= 0) ? 0.5 : (double) (current - minMpl) / range;
                int sigStep = Math.max(1, (int) Math.round(15.0 * x * (1.0 - x)));
                return Math.min(maxMpl, current + sigStep);
            case MIAD:
                // Multiplicative Increase
                return Math.min(maxMpl, Math.max(current + 1, (int) Math.round(current * 1.5)));
            default:
                return current;
        }
    }

    private void applyMpl(int newMpl) {
        this.currentMpl = newMpl;
        if (admissionController != null) {
            admissionController.setProcessorCapacity(newMpl);
            // Wake up waiters if we expanded capacity
            if (admissionController.getCapacityWaitManager() != null) {
                admissionController.getCapacityWaitManager().onCapacityAvailable();
            }
        }
    }
}
