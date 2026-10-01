package de.tum.tssimulator.experiments.support;

import java.util.Locale;

/**
 * TGS static-locking validation (Tay/Goodman/Suri parity plan): one row per FIRST admission
 * attempt (attemptIndex == 0) of one transaction, for one seed of one grid cell. Only first
 * attempts are materialized as rows - P1/P2 are computed on these (repeated same-resource-set
 * attempts against the same blocker are correlated, not independent draws - see
 * AdmissionAttemptProvenance's javadoc), and volume here is bounded by admitted-transaction
 * count per run, unlike the full attempt stream (see {@link AdmissionAttemptHistogramRow} for
 * that). Written by {@link AdmissionAttemptRowWriter}, populated from
 * {@code SimulationMetrics.getAdmissionAttempt*()} via {@code ExperimentRuntimeSnapshot}.
 */
public final class AdmissionAttemptRow {
    public final String configId;
    public final long seed;
    public final int d;
    public final int k;
    public final int mpl;
    public final double f;
    public final long virtualTime;
    public final int nRunning;
    public final int heldLockUnits;
    /** {@code AdmissionOutcome.name()} - ADMITTED / BLOCKED_AT_CHECK / LOST_RACE. Kept as a
     * String rather than the execution-package enum so experiments/support doesn't need a new
     * dependency on execution (mirrors SimulationMetrics.recordAdmissionAttempt()'s choice). */
    public final String outcome;

    public AdmissionAttemptRow(String configId, long seed, int d, int k, int mpl, double f,
            long virtualTime, int nRunning, int heldLockUnits, String outcome) {
        this.configId = configId;
        this.seed = seed;
        this.d = d;
        this.k = k;
        this.mpl = mpl;
        this.f = f;
        this.virtualTime = virtualTime;
        this.nRunning = nRunning;
        this.heldLockUnits = heldLockUnits;
        this.outcome = outcome;
    }

    public static String csvHeader() {
        return "config_id,seed,D,k,mpl,f,virtual_time_ms,n_running,held_lock_units,outcome";
    }

    public String toCsvRow() {
        return String.format(Locale.US, "%s,%d,%d,%d,%d,%.6f,%d,%d,%d,%s",
                configId, seed, d, k, mpl, f, virtualTime, nRunning, heldLockUnits, outcome);
    }
}
