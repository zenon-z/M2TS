package de.tum.tssimulator.experiments.support;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Relations between ARMS that follow from the design, checked on ladder output.
 *
 * <p>Every gate in this work has been single-arm: a throughput band, a work-per-completion ratio, a
 * histogram shape. The LOCK-phase removal passed 437 of 438 unit tests, a single-cell throughput
 * band, and a work-per-completion gate reading exactly 25.0000 - and was still wrong, because what
 * it broke was a RELATION BETWEEN ARMS (S2PL overtook C2PL, which the protocols make impossible).
 * No single-arm gate can express that. These can.
 *
 * <p>Only relations that follow from the DESIGN belong here, never ones that merely held in past
 * measurements. "The oracle leads dynamic at high N" is a result and is deliberately absent: it is
 * what the experiment exists to measure, and encoding a measured outcome as an invariant would make
 * the check assert its own conclusion.
 */
public final class CrossArmInvariants {

    /**
     * Fractional slack before a violation is reported. The arms are compared at the SAME seed and
     * each run is deterministic, so this is not noise headroom - it is there so a cell where the
     * two arms are legitimately near-equal (low mpl, little contention to separate them) does not
     * trip on the last decimal. Gross violations are what these checks are for; magnitude drift is
     * the band assertion's job.
     */
    private static final double SLACK = 0.02;

    private CrossArmInvariants() {
    }

    /** A cell key: which workload, at which mpl, on which seed. */
    public record Cell(String workload, int mpl, long seed) {
    }

    public record Violation(String invariant, Cell cell, double left, double right, String message) {
        @Override
        public String toString() {
            return message;
        }
    }

    /**
     * S2PL must not outrun C2PL at {@code operationBatchSize == 0}.
     *
     * <p>At b=0 the S2PL coordinator delegates to {@code acquireAllLocks}, so BOTH protocols take
     * the whole footprint atomically and the only remaining difference is
     * {@code shouldReleaseOnSoftDeny}: C2PL releases and retries, S2PL holds. S2PL therefore holds
     * every lock for at least as long as C2PL does on the same workload and seed, and holding
     * longer cannot produce more completions.
     *
     * <p><b>Scoped to b=0 deliberately.</b> Once the cursor engages, S2PL acquires incrementally and
     * holds a SMALLER set for most of its span, so it may legitimately beat C2PL. Applying this
     * check to an interleaved rung would fail a correct implementation.
     */
    public static List<Violation> s2plNeverOutrunsC2plAtWholeFootprint(
            Map<Cell, Double> c2plThroughput, Map<Cell, Double> s2plThroughput) {
        List<Violation> out = new ArrayList<>();
        for (Map.Entry<Cell, Double> e : s2plThroughput.entrySet()) {
            Double c2pl = c2plThroughput.get(e.getKey());
            if (c2pl == null || c2pl <= 0.0) {
                continue;
            }
            double s2pl = e.getValue();
            if (s2pl > c2pl * (1.0 + SLACK)) {
                out.add(new Violation("s2pl<=c2pl@b0", e.getKey(), s2pl, c2pl, String.format(
                        Locale.US,
                        "%s mpl=%d seed=%d: S2PL %.3f > C2PL %.3f (ratio %.3f). At b=0 S2PL holds "
                                + "locks strictly longer than C2PL, so it cannot complete more. "
                                + "The protocol distinction has been weakened or erased.",
                        e.getKey().workload(), e.getKey().mpl(), e.getKey().seed(),
                        s2pl, c2pl, s2pl / c2pl)));
            }
        }
        return out;
    }

    /**
     * The dynamic cell must not outrun the static cell at the same mpl.
     *
     * <p>The justification is WORK PER COMPLETED GROUP and nothing else. A static group is one
     * transaction ({@code semanticOverlap = 0}, so no CPRs); a dynamic group is a parent plus its
     * bookkeeper cascade and completes only when every member does. More member-completions per
     * group means fewer groups per unit time. That is arithmetic on work and holds under any
     * concurrency discipline.
     *
     * <p><b>This is NOT a protocol-controlled comparison, and must not be read as one.</b> Setting
     * {@code lockingProtocol = C2PL} does not make the dynamic cell a C2PL cell: its cascade spawns
     * bookkeepers that acquire as they are created, which is incremental hold-and-wait in character
     * whatever the nominal setting says. So "C2PL static vs C2PL dynamic" compares two different
     * effective disciplines as well as two workloads, and no conclusion about PROTOCOLS can be drawn
     * from the gap between them. (The two cells are more alike under S2PL, where the nominal setting
     * and the cascade's behaviour agree.)
     *
     * <p>What that leaves is a coarse structural check, not a sensitive one. Observed headroom is
     * enormous - 300.4 vs 6.2 at mpl=256 - so it fires only on something badly wrong, such as a
     * dynamic group being counted complete without its cascade.
     */
    public static List<Violation> dynamicNeverOutrunsStatic(Map<Cell, Double> throughputByCell) {
        List<Violation> out = new ArrayList<>();
        for (Map.Entry<Cell, Double> e : throughputByCell.entrySet()) {
            if (!"dynamic".equals(e.getKey().workload())) {
                continue;
            }
            Cell staticCell = new Cell("static", e.getKey().mpl(), e.getKey().seed());
            Double stat = throughputByCell.get(staticCell);
            if (stat == null || stat <= 0.0) {
                continue;
            }
            double dyn = e.getValue();
            if (dyn > stat * (1.0 + SLACK)) {
                out.add(new Violation("dynamic<=static", e.getKey(), dyn, stat, String.format(
                        Locale.US,
                        "mpl=%d seed=%d: dynamic %.3f > static %.3f. A dynamic group is a parent "
                                + "plus its whole cascade and completes only when every member "
                                + "does, so it cannot outrun a single-transaction group.",
                        e.getKey().mpl(), e.getKey().seed(), dyn, stat)));
            }
        }
        return out;
    }
}
