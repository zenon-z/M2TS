package de.tum.tssimulator.bootstrap;

import java.util.function.Supplier;

final class SimulationWatchdog {
    private static final int DEFAULT_MAX_STEPS_WITHOUT_PROGRESS = 10000;
    private static final String WATCHDOG_STEPS_PROPERTY = "ts.simulator.watchdog.maxStepsWithoutProgress";

    /**
     * The stall budget, in SIMULATED TIME. 100,000,000 is 10,000 steps at a 10,000-tick quantum,
     * which is where the step-denominated form of this guard was calibrated.
     *
     * <p><b>Denominating it in STEPS makes its meaning depend on {@code stepTimeSlice}</b>, since a
     * step advances virtual time by one slice: 10,000 steps tolerate 100 simulated seconds without
     * progress at a 10,000-tick quantum and only ONE second at a quantum of 100. Nothing about
     * "is this run stalled" changes when the scheduling quantum changes, but the guard's
     * strictness changes by the same factor - enough to truncate 4 of 6 dynamic runs in a slice
     * sweep, which then reads as a throughput collapse rather than as censored data.
     *
     * <p>Deriving the step count from a simulated-time budget instead makes it slice-invariant by
     * CONSTRUCTION rather than by nobody having changed the slice.
     */
    private static final long DEFAULT_MAX_SIMULATED_TIME_WITHOUT_PROGRESS = 100_000_000L;
    private static final String WATCHDOG_TIME_PROPERTY = "ts.simulator.watchdog.maxSimulatedTimeWithoutProgress";

    private final int maxStepsWithoutProgress;
    private int stepsWithoutProgress;
    private int lastCompletedOrAborted;

    public SimulationWatchdog(int maxStepsWithoutProgress) {
        this.maxStepsWithoutProgress = maxStepsWithoutProgress;
    }

    /**
     * Legacy step-denominated form. Prefer {@link #fromSystemProperties(long)}, which is
     * slice-invariant; this overload assumes the historical 10,000 quantum.
     */
    public static SimulationWatchdog fromSystemProperties() {
        return fromSystemProperties(10_000L);
    }

    /**
     * Derives the step budget from a SIMULATED-TIME budget and the run's own quantum, so the guard
     * means the same thing at every slice. An explicit step override still wins, for the case where
     * someone genuinely wants a step count.
     */
    public static SimulationWatchdog fromSystemProperties(long stepTimeSlice) {
        Integer explicitSteps = Integer.getInteger(WATCHDOG_STEPS_PROPERTY);
        if (explicitSteps != null) {
            return new SimulationWatchdog(explicitSteps);
        }
        long timeBudget = Long.getLong(WATCHDOG_TIME_PROPERTY, DEFAULT_MAX_SIMULATED_TIME_WITHOUT_PROGRESS);
        long slice = Math.max(1L, stepTimeSlice);
        long steps = Math.max(1L, (timeBudget + slice - 1) / slice);   // ceil, at least one step
        return new SimulationWatchdog((int) Math.min(steps, Integer.MAX_VALUE));
    }

    public SimulationWatchdogDiagnostics observe(long virtualTime, int completedCount, int abortedCount,
            Supplier<SimulationWatchdogDiagnostics> diagnosticsSupplier) {
        int currentProgress = completedCount + abortedCount;
        if (currentProgress == lastCompletedOrAborted) {
            stepsWithoutProgress++;
            if (stepsWithoutProgress >= maxStepsWithoutProgress) {
                return diagnosticsSupplier.get();
            }
            return null;
        }

        stepsWithoutProgress = 0;
        lastCompletedOrAborted = currentProgress;
        return null;
    }

    public int maxStepsWithoutProgress() {
        return maxStepsWithoutProgress;
    }
}
