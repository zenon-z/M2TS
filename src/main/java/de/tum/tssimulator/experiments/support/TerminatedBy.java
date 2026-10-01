package de.tum.tssimulator.experiments.support;

/**
 * F3 (VLDB reviewer response): explicit per-seed-run censoring reason, surfaced in every
 * RawRunRow so the analysis pipeline (D2) can exclude censored cells from breakpoint fitting
 * instead of silently treating a truncated run as a real throughput measurement.
 *
 * COMPLETION_TARGET is reserved/unused: no completion-target stop mode exists in the simulator
 * today (only FixedTimeStepStrategy/FixedTransactionStepStrategy, both budget-driven, not
 * "stop once N transactions have completed") - every non-watchdog run today ends via
 * TIME_LIMIT. Kept in the enum so a future completion-target StepStrategy doesn't require a
 * schema change.
 */
public enum TerminatedBy {
    COMPLETION_TARGET,
    TIME_LIMIT,
    WATCHDOG,
    // StepMode.FIXED_TRANSACTION's wall-clock runaway guard fired before the run
    // reached either its completion target or its (uniform-across-seeds, KM-safe) simulated-time
    // cap - see SimulatorContext.wallClockDeadlineMs's javadoc. Deliberately distinct from
    // WATCHDOG (a genuine zero-progress hang) - a run tagged this way WAS making progress, just
    // too slowly to finish within the real-time budget allotted. Distinct from TIME_LIMIT too:
    // TIME_LIMIT's censoring point is identical across every seed/config (independent of the
    // run's own dynamics, satisfying Kaplan-Meier's non-informative-censoring assumption); this
    // tag's censoring point is NOT (it correlates with how slow/starving the run was) - exclude
    // rows tagged this way from KM analysis, report them separately instead.
    WALL_CLOCK_RUNAWAY
}
