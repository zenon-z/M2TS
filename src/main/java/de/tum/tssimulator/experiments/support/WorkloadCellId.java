package de.tum.tssimulator.experiments.support;

import java.util.Locale;

/**
 * Cell identity for the W1-W4 workload-axis campaign: a canonical key over every axis, used both
 * as the join key between the raw rows and the sidecar, and as the input to
 * {@link SeedSweepRunner#cellSalt(String)}.
 *
 * <p><b>Why not {@link ConfigId}.</b> That key covers
 * {@code (f, mu, m, topology, mpl, k, D)} and has no slot for access skew, read-only mix,
 * transaction length or the core count. Cells differing only along one of those four would produce
 * an identical key - and, far worse, an identical seed salt, so
 * {@link SeedSweepRunner#saltedSeeds} would replay the SAME CPR graph and the SAME arrival draw at
 * every point of the new axis. That is the independence requirement failing silently:
 * the curve still gets drawn, and the residual correlation along the axis is invisible in it.
 *
 * <p><b>Terminology, because two different things are called k.</b> {@code txnLen} here is the
 * spec's k - TRANSACTION LENGTH ({@code constantTransactionSize}). The repo's {@code k} is
 * {@code admissionBatchSize}, which is what the {@code k} column of {@link RawRunRow} and
 * {@link ConfigId} means. They must never be written into each other's column.
 *
 * <p><b>Arm variables are excluded by construction</b> - there is no arm field. An arm must be
 * left out of the salt so that A/B arms at one cell are compared as a paired difference; if one is
 * ever added it belongs in the sidecar, not in this key.
 */
public record WorkloadCellId(
        double semanticOverlap,
        double mu,
        int m,
        String topology,
        double cprInitialAttractiveness,
        double zipfTheta,
        double readOnlyFraction,
        int cores,
        int txnLen) {

    /**
     * Readable, stable slug. Fixed field order and {@code Locale.US} so the string is identical on
     * every machine - it is a join key and a salt input, so a locale-dependent decimal separator
     * would silently split one cell into two.
     */
    public String key() {
        return String.format(Locale.US,
                "f%.4f_mu%.2f_m%d_%s_a%.4f_th%.3f_ro%.3f_c%d_klen%d",
                semanticOverlap, mu, m, topology, cprInitialAttractiveness, zipfTheta,
                readOnlyFraction, cores, txnLen);
    }

    /** Per-cell seed salt over every axis. See {@link SeedSweepRunner#cellSalt(String)}. */
    public long salt() {
        return SeedSweepRunner.cellSalt(key());
    }

    @Override
    public String toString() {
        return key();
    }
}
