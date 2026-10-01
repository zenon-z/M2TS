package test.java.experiments.support;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import de.tum.tssimulator.experiments.support.CrossArmInvariants.Cell;
import de.tum.tssimulator.experiments.support.CrossArmInvariants.Violation;
import de.tum.tssimulator.experiments.support.CrossArmInvariants;

/**
 * Verified against the real datasets that motivated the check, not synthetic stand-ins.
 *
 * <p>Every number below is a measured rung-0 static throughput at k=25, f=0.47, mpl=256, five seeds:
 * the pre-removal baseline (which must pass) and the LOCK-phase-removal run that the ladder
 * falsified (which must fail). That is verification by removal using the actual failing data - the
 * check is only worth having if it would have caught the patch it was written for.
 */
public class CrossArmInvariantsTest {

    private static final long[] SEEDS = { 1000003L, 2000006L, 3000009L, 4000012L, 5000015L };

    // Measured rung-0, static, mpl=256, five seeds.
    private static final double[] C2PL_PRE = { 300.685, 300.620, 300.715, 299.895, 300.285 };
    private static final double[] S2PL_PRE = { 42.130, 43.085, 43.205, 45.105, 42.730 };
    private static final double[] C2PL_PHASE_WORK = { 270.380, 271.020, 270.420, 270.365, 270.670 };
    private static final double[] S2PL_PHASE_WORK = { 345.800, 348.155, 349.965, 346.935, 344.055 };

    private static Map<Cell, Double> cells(String workload, double[] throughputs) {
        Map<Cell, Double> m = new LinkedHashMap<>();
        for (int i = 0; i < SEEDS.length; i++) {
            m.put(new Cell(workload, 256, SEEDS[i]), throughputs[i]);
        }
        return m;
    }

    @Test
    public void theBaselineSatisfiesTheProtocolInvariant() {
        List<Violation> v = CrossArmInvariants.s2plNeverOutrunsC2plAtWholeFootprint(
                cells("static", C2PL_PRE), cells("static", S2PL_PRE));
        Assertions.assertTrue(v.isEmpty(),
                "pre-removal data must satisfy S2PL <= C2PL, but got: " + v);
    }

    @Test
    public void theFalsifiedPhaseWorkViolatesTheProtocolInvariantOnEverySeed() {
        List<Violation> v = CrossArmInvariants.s2plNeverOutrunsC2plAtWholeFootprint(
                cells("static", C2PL_PHASE_WORK), cells("static", S2PL_PHASE_WORK));
        // Count asserted separately from content: "did it fire" and "did it fire for the right
        // reason" fail for different reasons and a single assertion cannot tell them apart.
        Assertions.assertEquals(SEEDS.length, v.size(),
                "the LOCK-phase-removal run inverts the protocol ratio on all five seeds");
        Assertions.assertTrue(v.get(0).message().contains("holds locks strictly longer"),
                "the violation should say WHY it is impossible, not just that it happened: "
                        + v.get(0).message());
    }

    @Test
    public void nearEqualArmsInsideTheSlackDoNotFire() {
        // mpl=4 post-removal: S2PL 33.094 vs C2PL 33.848, ratio 0.978. Legitimately near-equal at
        // low contention - this must NOT be reported, or the check becomes noise and gets ignored.
        Map<Cell, Double> c2pl = Map.of(new Cell("static", 4, 1000003L), 33.848);
        Map<Cell, Double> s2pl = Map.of(new Cell("static", 4, 1000003L), 33.094);
        Assertions.assertTrue(
                CrossArmInvariants.s2plNeverOutrunsC2plAtWholeFootprint(c2pl, s2pl).isEmpty());
    }

    @Test
    public void dynamicOutrunningStaticIsReported() {
        Map<Cell, Double> m = new LinkedHashMap<>();
        m.put(new Cell("static", 256, 1000003L), 300.685);
        m.put(new Cell("dynamic", 256, 1000003L), 6.176);
        Assertions.assertTrue(CrossArmInvariants.dynamicNeverOutrunsStatic(m).isEmpty(),
                "measured rung-0 values satisfy dynamic <= static by a wide margin");

        // A dynamic group completing without its cascade would look like this.
        m.put(new Cell("dynamic", 256, 1000003L), 400.0);
        List<Violation> v = CrossArmInvariants.dynamicNeverOutrunsStatic(m);
        Assertions.assertEquals(1, v.size());
        Assertions.assertTrue(v.get(0).message().contains("parent plus its whole cascade"));
    }
}
