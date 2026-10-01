package de.tum.tssimulator.experiments.support;

import de.tum.tssimulator.config.SimulatorParameters;

/**
 * V3 criterion 3 / F2's crossover: reconciler-pool load, computed from the CONFIGURATION.
 *
 * <pre>
 *   rho_offered = lambda_offered * S / N     DECIDES.  STABLE iff &lt; 1
 *   rho_created = lambda_created * S / N     utilisation. DIAGNOSTIC
 * </pre>
 *
 * <p><b>Which lambda decides is the whole point, and the obvious choice is wrong.</b> Created CUs
 * are ENDOGENOUS: when the pool cannot keep up the dirty set fills, the collapse fraction rises,
 * fewer CUs are created, and {@code rho_created} settles at just under 1 <i>from below</i>. A
 * saturated cell then reads 0.98 and passes {@code rho < 1}, so "smallest N with rho &lt; 1" would
 * return N=1 at every load. The load that decides must be the load OFFERED before collapse sheds
 * any of it.
 *
 * <p>{@code lambda_offered} is exogenous in N - by criterion 2 the producer does not wait for
 * drains, so foreground throughput, and hence the CU offer rate, is flat in N. That is what makes a
 * crossover computable from a handful of cells rather than the whole axis.
 *
 * <p><b>The identity that anchors the grid:</b>
 *
 * <pre>rho_created  ~=  min(rho_offered, 1)</pre>
 *
 * Below the crossover the two coincide - nothing is shed. Above it, {@code rho_created} pins at the
 * ceiling and the deficit is exactly the collapse fraction. That is an M1-style analytical anchor
 * and it states F2's collapse story in one line.
 *
 * <p><b>PostgreSQL measures the inputs, not the outcome:</b> {@code lambda_offered} from the outbox
 * insert ATTEMPT rate (including rows the unique partial index rejects), {@code S} from per-row
 * drain time ({@code processed_at} minus dequeue, at batch size 1), {@code N} known.
 *
 * <p>This replaces two estimator designs that tried to infer stability from the backlog series. It
 * does not need inferring - it is arithmetic.
 */
public final class QueueingRho {

    private QueueingRho() {
    }

    /**
     * Service time of one CU, in ticks.
     *
     * <p>BASE zeroes the lock phase and a reconciler runs a CU through IO then CPU serially, so the
     * cost is the sum. CUs are single-resource - {@code DirtyKeyUtil.fromBookkeeper} throws
     * otherwise - so there is no per-resource multiplier.
     */
    public static long cuServiceTicks(SimulatorParameters params) {
        return params.ioTimePerResource + params.cpuTimePerResource;
    }

    /**
     * @param cus CUs arriving over the window. Pass the OFFERED count for the gating rho, the
     *     CREATED count for the utilisation diagnostic.
     * @param windowTicks simulated duration those CUs arrived over.
     * @return rho, or {@link Double#NaN} when the window or reconciler count is zero.
     */
    public static double rho(long cus, long windowTicks, SimulatorParameters params) {
        int reconcilers = params.numberOfReconcilerProcessors;
        if (windowTicks <= 0 || reconcilers <= 0) {
            return Double.NaN;
        }
        return (cus / (double) windowTicks) * cuServiceTicks(params) / reconcilers;
    }

    /**
     * STABLE iff {@code rhoOffered < 1} - the drain keeps up WITHOUT relying on collapse to shed
     * load. Pass the offered rho; passing the created one makes every cell look stable.
     *
     * <p>A NaN rho is not stable: an unevaluable cell fails rather than passes.
     */
    public static boolean stable(double rhoOffered) {
        return !Double.isNaN(rhoOffered) && rhoOffered < 1.0;
    }

    /** Smallest N in {@code ladder} whose offered rho is below 1, or -1 if none is. */
    public static int crossover(long cusOffered, long windowTicks, SimulatorParameters base,
            int[] ladder) {
        for (int n : ladder) {
            SimulatorParameters at = SeedSweepRunner.copyBuilder(base)
                    .numberOfReconcilerProcessors(n)
                    .build();
            if (stable(rho(cusOffered, windowTicks, at))) {
                return n;
            }
        }
        return -1;
    }

    /**
     * The grid's analytical anchor: {@code rho_created ~= min(rho_offered, 1)}.
     *
     * @return the signed deviation from that identity; near zero is the expected result.
     */
    public static double identityResidual(double rhoOffered, double rhoCreated) {
        return rhoCreated - Math.min(rhoOffered, 1.0);
    }
}
