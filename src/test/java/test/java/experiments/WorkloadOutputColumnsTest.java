package test.java.experiments;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import de.tum.tssimulator.bootstrap.ExperimentRuntimeSnapshot;

/**
 * Every column the plan's verification items name must exist in the W1-W4 output headers, checked
 * at BUILD time rather than discovered at analysis.
 *
 * <h2>Why this gate exists</h2>
 *
 * <p>Phase 1.5's realised-workload metrics were specified in the plan, referenced in three
 * javadocs, and <b>never implemented</b>. The campaign would have produced figures whose x-axes
 * were nominal theta and nominal read-only fraction with no realised counterpart to read them
 * against, and four verification items would have been unrunnable.
 *
 * <p><b>That is a worse failure than the zero it was meant to prevent.</b> A counter reading
 * zero because nothing wired it is at least PRESENT and suspicious; an absent column reads as a
 * schema difference, and in a 900-run campaign nobody notices a column that was never there. The
 * reachability test guards the input side of exactly this ({@code SimulatorParameterReachabilityTest}:
 * every config field reaches the engine or is exempted by name with a reason). This is its output
 * side.
 *
 * <p>Deliberately a LIST OF NAMES rather than a reflective sweep: the obligation comes from the
 * plan's verification items, not from the code's shape, so the test has to state what the plan
 * promised. A column dropped from the header fails here with the item it belongs to.
 */
class WorkloadOutputColumnsTest {

    private static final Path DRIVER =
            Path.of("src/main/java/de/tum/tssimulator/experiments/WorkloadAxisSweep.java");

    /** Column, and the verification item that requires it. */
    private static final String[][] REQUIRED_RAW = {
        {"realized_top1pct_access_share", "item 7 (C7 effect: theta must move it) and Exp 1's x-axis"},
        {"mean_distinct_instances_touched", "item 15 (F4 saturation check, read against k)"},
        {"writes_per_txn_mean", "item 9 (two-class arithmetic: ceil(n/2) at ro=0)"},
        {"read_only_txn_frac_realized", "item 9 (realised vs nominal read-only share)"},
        {"cpr_edges_fired_per_completion", "item 7 (readOnlyTransactionFraction must move it)"},
        {"admitted_parents_for_access", "item 8 (C15: nonzero proves the counter is wired)"},
        {"frac_blocked", "per-row realised contention - the band is calibrated at ONE cell only"},
        {"terminated_by", "C5: never pool across termination paths"},
    };

    private static final String[][] REQUIRED_CELLS = {
        {"total_locks", "Phase 5.1 audit - RawRunRow does not record the lock budget"},
        {"lock_granularity", "item 13 (aliasing provably off: ceil(D/totalLocks) == 1)"},
        {"pigeonhole_ratio", "item 14 (emitted on EVERY cell, pass or fail)"},
        {"cpr_alpha", "Panel D's axis - must be recoverable from the row"},
        {"service_demand_ratio", "interaction multiplier denominator"},
        {"reference_capacity_tps", "the control-verified capacity the figures are read against"},
        {"seeds_used", "plan 0.10: seed escalation is POINT-TARGETED, so cells carry unequal n. "
                + "A footnote is retrospective narration written once the escalations are known, "
                + "and goes stale if the stratified-redraw branch is taken instead of n=15; a "
                + "column is true by construction and joins against the per-seed sdr spread"},
    };

    @Test
    void driverEmitsEveryColumnTheVerificationItemsName() throws IOException {
        String src = Files.readString(DRIVER);
        List<String> missing = new ArrayList<>();
        for (String[] req : REQUIRED_RAW) {
            if (!src.contains('"' + req[0] + ',') && !src.contains(req[0] + ',')
                    && !src.contains(req[0] + '"')) {
                missing.add(req[0] + "  <- required by " + req[1]);
            }
        }
        for (String[] req : REQUIRED_CELLS) {
            if (!src.contains(req[0])) {
                missing.add(req[0] + "  <- required by " + req[1]);
            }
        }
        if (!missing.isEmpty()) {
            fail("WorkloadAxisSweep's output headers are missing columns the plan's verification "
                    + "items depend on. An ABSENT column reads as a schema difference at analysis "
                    + "time, not as a defect - which is why this is checked here:\n  "
                    + String.join("\n  ", missing));
        }
    }

    /**
     * The snapshot must actually expose the realised metrics, not merely have the driver name
     * them - the failure being guarded against was a plan that referenced metrics nothing
     * computed.
     */
    @Test
    void snapshotExposesTheRealisedWorkloadAccessors() {
        Set<String> accessors = new LinkedHashSet<>();
        Arrays.stream(ExperimentRuntimeSnapshot.class.getMethods())
                .forEach(mth -> accessors.add(mth.getName()));
        for (String needed : new String[] {
            "realizedTop1pctAccessShare", "realizedTop10pctAccessShare",
            "meanDistinctInstancesTouched", "writesPerTxnMean",
            "readOnlyTxnFractionRealized", "admittedParentsForAccess"}) {
            assertTrue(accessors.contains(needed),
                    "ExperimentRuntimeSnapshot has no " + needed + "() - the plan names this metric "
                            + "but nothing computes it, which is the exact gap this test exists for");
        }
    }
}
