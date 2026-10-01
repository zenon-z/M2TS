package de.tum.tssimulator.experiments.support;

import de.tum.tssimulator.bookkeeping.CprTopologyMode;
import de.tum.tssimulator.bookkeeping.ReferentialAction;
import de.tum.tssimulator.config.ConcurrencyModel;
import de.tum.tssimulator.config.SimulatorParameters;
import de.tum.tssimulator.config.TransactionSizeProfile;
import de.tum.tssimulator.experiments.support.SeedSweepRunner;

/**
 * Shared baseline for corrected BASE experimental-design runs.
 */
public final class BaseExperimentalDesignDefaults {
    private BaseExperimentalDesignDefaults() {
    }

    public static SimulatorParameters baseParameters() {
        // 15 foreground + 1 reconciler - which is what withEffectiveProcessors(15, 1) always meant.
        //
        // CORRECTION to the comment that stood here: it claimed this base "has always had SIXTEEN
        // foreground units". It has not. The pool is 16, but BASE's carve-out branch in
        // SimulatorConfig.fromParameters takes the reconciler out of it, leaving FIFTEEN running the
        // foreground. The hash-identical pin was right; the reasoning attached to it was not.
        //
        // No V0 result is at risk either way: V0's plan states that numberOfIoProcessors "has no
        // effect on any prediction and cannot be fitted".
        return withForegroundFixed(15, 1)
                .totalModels(10)
                .totalResources(5000)
                .ioTimePerResource(200)
                .cpuTimePerResource(100)
                .lockIOTimePerResource(0)
                .lockCPUTimePerResource(0)
                .initialTransactions(100)
                .totalTransactionSteps(10000)
                .maxTransactionModels(5)
                .maxTransactionResources(100)
                .useConstantTransactionSize(false)
                .transactionSizeProfile(TransactionSizeProfile.CONSTANT)
                .constantTransactionSize(50)
                .smallTransactionSize(10)
                .largeTransactionSize(100)
                .largeTransactionProbability(0.5)
                .useRandomResourceSelection(false)
                .semanticOverlap(0.10)
                .maxCprsPerResource(5)
                .avgCprsPerResource(2.0)
                .cprTopologyMode(CprTopologyMode.UNIFORM)
                .referentialAction(ReferentialAction.ON_CASCADE)
                .concurrencyModel(ConcurrencyModel.BASE)
                .simulationDurationSeconds(10000)
                .totalLocks(5000)
                .transactionSamplingSeed(12345L)
                .semanticOverlapSeed(67890L)
                .build();
    }

    /**
     * FIXED FOREGROUND: the IO/CPU pools hold {@code foreground} units and {@code reconcilers} more
     * are created alongside, so the TOTAL grows with R.
     *
     * <p>This is what V3 needs. Criterion 2 - "foreground throughput flat in N" - assumes the
     * producer's capacity does not move with the drain count, and that is also what makes
     * {@code lambda_offered} exogenous in N, which is what lets a crossover be placed from a handful
     * of cells rather than the whole axis.
     *
     * <p>Bookkeepers run on the reconciler units whenever {@code reconcilers > 0} (falling back to
     * the foreground pool only at zero), so a reconciler unit is a self-contained server: one CU
     * costs {@code io + cpu} on one unit. That is what makes {@code S/N} in {@code QueueingRho}
     * correct as written.
     */
    public static SimulatorParameters.Builder withForegroundFixed(int foreground, int reconcilers) {
        // F + R, NOT F. SimulatorConfig.fromParameters carves the reconcilers OUT of the pool
        // under BASE (effectiveCpu = cpu - reconcilers), so asking for F leaves F - R running the
        // foreground. An N=1/N=24 pair then compares 14 units against 15 and reads as a 7%
        // failure of the throughput criterion rather than as a setup error.
        return withForegroundFixed(SimulatorParameters.builder(), foreground, reconcilers);
    }

    /**
     * The same convention applied to an EXISTING builder - for callers that start from a frozen base
     * via {@code SeedSweepRunner.copyBuilder} rather than from a bare builder.
     *
     * <p><b>One implementation, deliberately.</b> Re-implementing
     * {@code foreground + reconcilers} inline in a driver puts the convention in two places, and a
     * convention with two implementations has two places to be wrong and one place to be fixed.
     */
    public static SimulatorParameters.Builder withForegroundFixed(SimulatorParameters.Builder builder,
            int foreground, int reconcilers) {
        if (foreground < 1) {
            throw new IllegalArgumentException("foreground must be at least 1: " + foreground);
        }
        if (reconcilers < 0) {
            throw new IllegalArgumentException("reconcilers cannot be negative: " + reconcilers);
        }
        int pool = foreground + reconcilers;
        return builder
                .numberOfIoProcessors(pool)
                .numberOfCpuProcessors(pool)
                .numberOfReconcilerProcessors(reconcilers);
    }

    /**
     * FIXED TOTAL: {@code total} units are split, {@code reconcilers} to the drain and the rest to
     * the foreground, so the budget does not move with R.
     *
     * <p>This is what F3's equal-budget comparison against ACID needs - ACID gets {@code total}
     * foreground units and no reconcilers, BASE gets {@code total - R} and R. Under it, foreground
     * throughput FALLS with R by design, so V3's criterion 2 would fail for the right reason. The
     * two conventions cannot be served by one helper, which is why the old
     * {@code withEffectiveProcessors} - which did neither, adding R to the foreground pool AND
     * creating R units on top - is gone.
     */
    public static SimulatorParameters.Builder withTotalBudget(int total, int reconcilers) {
        if (reconcilers >= total) {
            throw new IllegalArgumentException("reconcilers=" + reconcilers
                    + " must leave at least one foreground unit out of total=" + total);
        }
        // T, not T - R. The engine's carve-out branch does the subtraction, so asking for T - R
        // yields T - 2R. Same trap as withForegroundFixed above, in the other direction.
        return SimulatorParameters.builder()
                .numberOfIoProcessors(total)
                .numberOfCpuProcessors(total)
                .numberOfReconcilerProcessors(reconcilers);
    }

    /**
     * Delegates to {@link SeedSweepRunner#copyBuilder}.
     *
     * <p>ONE copy list, not a hand-maintained one per experiment. Builder.build() substitutes a
     * default for anything left unset, so a field omitted from a copy is not an error but a
     * different simulation - which has already cost one confirmed bug here, propertiesPerModel
     * and instancePoolSize missing from a copy so that an A/B arm ran D=4950 against an intended
     * 4800, caught only by a suspiciously identical rerun. Delegation means adding a field to
     * SimulatorParameters cannot silently bypass any of these call sites.
     */
    public static SimulatorParameters.Builder copyBuilder(SimulatorParameters params) {
        return SeedSweepRunner.copyBuilder(params);
    }
}
