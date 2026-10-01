package de.tum.tssimulator.execution;

import java.util.Locale;

import de.tum.tssimulator.config.SimulatorConfig;
import de.tum.tssimulator.core.TransactionState;
import de.tum.tssimulator.core.TransactionStateCounter;

class FixedTimeStepStrategy implements StepStrategy {
    private final StepContext ctx;
    private final long timeSlice;
    private final long totalDuration;
    private long virtualTime = 0;
    private int stepCount = 0;
    private static final int RETRY_INTERVAL = 10; // Retry blocked transactions every 10 steps

    // TGS static-locking validation: "instrument before reading" diagnostic - one line per step
    // showing PENDING count, admission attempts issued, admissions granted, and completions this
    // step, to localize the PENDING-pile-up throttle the FSM histogram found. Opt-in
    // (-Dts.debug.perStepLog=true), read once at construction like the repo's other -Dts.*
    // diagnostic toggles.
    private static final boolean PER_STEP_LOG = Boolean.getBoolean("ts.debug.perStepLog");

    public FixedTimeStepStrategy(long timeSlice,
                                 long totalDuration,
                                 StepContext ctx) {
        this.timeSlice = timeSlice;
        this.totalDuration = totalDuration;
        this.ctx = ctx;
    }

    private long lastSampledSecond = -1;   // see the sampling block in step()

    @Override
    public StepOutcome step() {
        // Maintain pool at initial transaction count (replenish as transactions complete)
        ctx.poolManager.maintain();
        
        // Transactions are retried when locks are released (SS2PLLockManager.releaseLocks)
        // No periodic retry needed
        
        // Update virtual time in context for exponential backoff
        ctx.virtualTime = virtualTime;
        
        // Update virtual time in dispatcher for exponential backoff
        if (ctx.dispatcher instanceof PhaseDispatcherGroup) {
            // Dispatcher is a group - need to update intake dispatcher specifically
            // For now, rely on ctx.virtualTime being accessible
        }
        
        int pendingBefore = 0;
        int completedBefore = 0;
        if (PER_STEP_LOG) {
            pendingBefore = TransactionStateCounter.getCount(TransactionState.PENDING);
            completedBefore = ctx.logger.metrics().getGroupsCompletedTotal();
        }

        // ===== DISPATCH: This processes all work for this simulated second =====
        // Includes: admission, lock acquisition, IO, CPU, reconciliation (BASE)
        long used = ctx.dispatcher.dispatch(timeSlice, virtualTime);

        if (PER_STEP_LOG) {
            int attemptsIssued = ctx.logger.metrics().getAndResetStepAdmissionAttemptsCount();
            int admissionsGranted = ctx.logger.metrics().getAndResetStepAdmissionsGrantedCount();
            int pendingAfter = TransactionStateCounter.getCount(TransactionState.PENDING);
            int completionsThisStep = ctx.logger.metrics().getGroupsCompletedTotal() - completedBefore;
            System.out.printf(Locale.US,
                    "[PER-STEP] step=%d t=%d pendingBefore=%d attemptsIssued=%d admissionsGranted=%d "
                            + "completions=%d pendingAfter=%d used=%d%n",
                    stepCount, virtualTime, pendingBefore, attemptsIssued, admissionsGranted,
                    completionsThisStep, pendingAfter, used);
        }

        // ===== SAMPLE DIRTY SET: After all work for this second completes =====
        // BASE mode: this is AFTER reconciliation runs
        // ACID mode: dirtySetManager will be null (skip sampling)
        // ONE SAMPLE PER SIMULATED SECOND, not per step.
        //
        // Sampling every STEP is the same thing only while a step IS a second
        // (stepTimeSlice = 1,000,000 ticks). At the finer quantum the service-quanta floor
        // requires it is not: at a 10-tick slice a 20 s run takes 2,000,000 steps, which
        // overflows the sample array and turns the cross-arm cadence - one sample per
        // stepTimeSlice of simulated time, which PostgreSQL's sampler must match - into a 10 us
        // cadence no real sampler can match.
        //
        // Decoupling restores both: the array is sized by DURATION, and the cadence the plan
        // actually meant (once a second, matchable by a PostgreSQL sampler) holds at any slice.
        //
        // RATE IS CONFIGURABLE because 1 Hz may be PHASE-LOCKED TO THE LOAD. Parents commit at
        // ~1,000/s, so a once-per-simulated-second sample fires at a parent-commit instant every
        // time - and the dirty set peaks at exactly those instants. V3 measured mean 24% of K from
        // 20 such samples (p50 = 0%, p95 = 84%) where Little's law gives a 3.5% TIME average: a 7x
        // gap that is either a real offer-weighted occupancy or an aliasing artifact.
        //
        // It matters beyond V3: this same estimator feeds dirty_post_recon_mean_pct, F3's staleness
        // axis, in every BASE experiment. -Dts.sim.dirtySampleHz raises the rate to settle it;
        // default 1 leaves every collected result bit-identical.
        if (ctx.dirtySetManager != null && ctx.logger != null) {
            long tick = virtualTime
                    / SimulatorConfig.dirtySamplePeriodTicks();
            if (tick > lastSampledSecond) {
                lastSampledSecond = tick;
                ctx.logger.metrics().sampleDirtySetSize(ctx.dirtySetManager.size());
            }
        }
        
        virtualTime += timeSlice;
        stepCount++;
        boolean finished = virtualTime >= totalDuration;
        return new StepOutcome(virtualTime, (int) (used > 0 ? 1 : 0), finished);
    }
}
