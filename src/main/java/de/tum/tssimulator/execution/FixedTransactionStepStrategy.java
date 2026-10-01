package de.tum.tssimulator.execution;

class FixedTransactionStepStrategy implements StepStrategy {
    private final StepContext ctx;
    private final int totalTransactionSteps;
    // A TRANSACTION COUNT, not a step count. Counting steps and multiplying by 1000 to
    // synthesize a virtual-time clock (stepCount*1000) charges a fixed 1 synthetic second per
    // outer-loop iteration, regardless of how much REAL service time that iteration consumed.
    // Because dispatch() below is called with an UNBOUNDED time budget (Long.MAX_VALUE), a
    // single iteration can drain far more than 1000ms worth of real work (each processor loops
    // internally up to MAX_TRANSACTIONS_PER_STEP=10,000 - see SimulatedProcessorUnit.step()) -
    // so the synthetic clock ran fast relative to true elapsed simulated time whenever the
    // system was busy, which was constantly, for any config actually worth measuring. Confirmed
    // empirically: throughput computed from a FIXED_TRANSACTION-mode run for Config B at MPL=32
    // came out ~10x the throughput measured for the IDENTICAL config under FIXED_TIME mode.
    // Fixed by tracking real elapsed virtual time directly, advanced by `used` (the actual
    // simulated time each step's dispatch() call consumed) instead of a synthetic per-step
    // increment - now the same clock semantics as FixedTimeStepStrategy's timeSlice advance,
    // just variable-sized per step instead of fixed-size.
    private long virtualTime = 0;
    // a run capped by WALL-CLOCK time gets censored at a point that
    // correlates with how slow/starving that specific run was (slow runs censor at an earlier
    // point in THEIR OWN simulated-time progression than fast runs do at the same real-time
    // budget) - that's informative censoring, which violates Kaplan-Meier's core independence
    // assumption and biases the survival curve toward whatever effect is being measured. Fixed by
    // capping on SIMULATED time instead (identical across every seed/config - see
    // simulatedTimeCap below), which censors every run at the exact same point in its own
    // timeline regardless of how it got there. wallClockDeadlineMs is now purely a generous
    // runaway guard for a pathologically slow run - SimulationRunner tags a run that hits it
    // TerminatedBy.WALL_CLOCK_RUNAWAY (not TIME_LIMIT) precisely so it gets excluded from KM
    // analysis rather than silently contaminating it.
    private final long simulatedTimeCap;
    private final long wallClockDeadlineMs;
    private static final int RETRY_INTERVAL = 10; // Retry blocked transactions every 10 steps

    public FixedTransactionStepStrategy(int totalTransactionSteps, StepContext ctx,
            long simulatedTimeCap, long wallClockDeadlineMs) {
        this.totalTransactionSteps = totalTransactionSteps;
        this.ctx = ctx;
        this.simulatedTimeCap = simulatedTimeCap;
        this.wallClockDeadlineMs = wallClockDeadlineMs;
    }

    @Override
    public StepOutcome step() {
        ctx.poolManager.maintain();

        // Periodic retry not needed - TransactionIntakePhaseDispatcher continuously pulls from READY queue

        long used = ctx.dispatcher.dispatch(Long.MAX_VALUE, virtualTime);
        virtualTime += used;
        // BUGFIX 3: FixedTimeStepStrategy sets ctx.virtualTime every step (for
        // exponential backoff AND for SimulationRunner.captureOutcome()'s end-of-run censored-
        // observation capture, which reads ctx.virtualTime as "now"); this class never did,
        // leaving it stuck at its default 0 for the whole run. Every right-censored group's
        // lifetime came out as submissionTime - 0 = a small/zero value instead of its true
        // elapsed time, silently defeating the entire point of right-censoring (capturing how
        // long the stuck tail has been stuck). Caught by inspecting the raw CSV, not by any
        // aggregate check - every censored row showed exactly lifetime_s=0.0.
        ctx.virtualTime = virtualTime;
        // BUGFIX 1: stop once groupsCompletedTotal reaches totalTransactionSteps,
        // not once outer-loop iterations do - see this class's earlier fix history for why the
        // original stepCount-based check was a disguised virtual-time budget, not a
        // completion-count target, despite what "totalTransactionSteps" suggests.
        int completed = ctx.logger.metrics().getGroupsCompletedTotal();
        // Three independent stop conditions - whichever fires first ends the run. The caller
        // (SimulationRunner.runFixedTransactionAllowPartial()) distinguishes which one fired
        // after the fact by comparing groupsCompletedTotal/virtualTime against the known
        // targets, rather than this class exposing a reason code - keeps StepOutcome/StepStrategy
        // unchanged.
        boolean finished = completed >= totalTransactionSteps
                || virtualTime >= simulatedTimeCap
                || System.currentTimeMillis() >= wallClockDeadlineMs;
        return new StepOutcome(virtualTime, (int) (used > 0 ? 1 : 0), finished);
    }
}

