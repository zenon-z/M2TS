package test.java.experiments.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import de.tum.tssimulator.config.SimulatorParameters;
import de.tum.tssimulator.experiments.support.BaseExperimentalDesignDefaults;
import de.tum.tssimulator.experiments.support.QueueingRho;
import de.tum.tssimulator.experiments.support.SeedSweepRunner;

/**
 * V3 criterion 3 / F2's crossover.
 *
 * <p>The case that matters is {@link #createdLambdaIsEndogenousAndWouldHideSaturation()}: created
 * CUs are endogenous, so a saturated cell reads a created-rho just under 1 and would pass. The
 * verdict must use OFFERED load.
 */
class QueueingRhoTest {

    /** Divisible by S (io + cpu = 300 at BASE defaults) so the fixtures' integer division is exact. */
    private static final long WINDOW = 3_000_000L;

    private static final int[] LADDER = {1, 2, 4, 8, 12, 16, 24};

    private static SimulatorParameters withReconcilers(int n) {
        return SeedSweepRunner.copyBuilder(BaseExperimentalDesignDefaults.baseParameters())
                .numberOfReconcilerProcessors(n)
                .build();
    }

    private static long cusFor(double servers) {
        return (long) (servers * WINDOW / QueueingRho.cuServiceTicks(withReconcilers(1)));
    }

    @Test
    @DisplayName("S is io + cpu per CU - BASE zeroes the lock phase and CUs are single-resource")
    void serviceTimeIsIoPlusCpu() {
        SimulatorParameters p = withReconcilers(1);
        assertEquals(p.ioTimePerResource + p.cpuTimePerResource, QueueingRho.cuServiceTicks(p));
        assertEquals(0, p.lockIOTimePerResource, "BASE's lock phase must stay free, or S is wrong");
        assertEquals(0, p.lockCPUTimePerResource);
    }

    @Test
    @DisplayName("rho scales inversely with N")
    void rhoScalesWithReconcilerCount() {
        long cus = cusFor(2.0);
        assertEquals(2.0, QueueingRho.rho(cus, WINDOW, withReconcilers(1)), 1e-9);
        assertEquals(1.0, QueueingRho.rho(cus, WINDOW, withReconcilers(2)), 1e-9);
        assertEquals(0.5, QueueingRho.rho(cus, WINDOW, withReconcilers(4)), 1e-9);

        assertFalse(QueueingRho.stable(1.0), "exactly at the ceiling is not below it");
        assertTrue(QueueingRho.stable(0.5));
    }

    @Test
    @DisplayName("THE TRAP: created lambda is endogenous and would hide saturation")
    void createdLambdaIsEndogenousAndWouldHideSaturation() {
        // A cell offered 8 servers' worth of work, drained by 2. It is deeply saturated.
        long offered = cusFor(8.0);
        SimulatorParameters p = withReconcilers(2);

        // Collapse sheds load until the pool is just busy: created settles at ~2 servers' worth,
        // i.e. rho_created just under 1. That is what an overloaded BASE cell actually does.
        long created = (long) (cusFor(2.0) * 0.98);

        double rhoOffered = QueueingRho.rho(offered, WINDOW, p);
        double rhoCreated = QueueingRho.rho(created, WINDOW, p);

        assertEquals(4.0, rhoOffered, 1e-6, "offered load is 4x what two reconcilers can serve");
        assertTrue(rhoCreated < 1.0, "created load self-limits below the ceiling");

        assertFalse(QueueingRho.stable(rhoOffered), "the offered rho correctly fails this cell");
        assertTrue(QueueingRho.stable(rhoCreated),
                "and the created rho would have PASSED it - which is why it must not gate");
    }

    @Test
    @DisplayName("the identity anchor: rho_created ~= min(rho_offered, 1)")
    void identityHoldsOnBothSidesOfTheCrossover() {
        SimulatorParameters p = withReconcilers(4);

        // Below the crossover nothing is shed, so the two coincide.
        long under = cusFor(2.0);
        double offeredUnder = QueueingRho.rho(under, WINDOW, p);
        assertEquals(0.5, offeredUnder, 1e-9);
        assertEquals(0.0, QueueingRho.identityResidual(offeredUnder, offeredUnder), 1e-9);

        // Above it, created pins at the ceiling and the deficit is the collapse fraction.
        long overOffered = cusFor(8.0);
        long overCreated = cusFor(4.0);
        double rhoOffered = QueueingRho.rho(overOffered, WINDOW, p);
        double rhoCreated = QueueingRho.rho(overCreated, WINDOW, p);
        assertEquals(2.0, rhoOffered, 1e-9);
        assertEquals(1.0, rhoCreated, 1e-9);
        assertEquals(0.0, QueueingRho.identityResidual(rhoOffered, rhoCreated), 1e-9);

        double collapseFraction = 1.0 - overCreated / (double) overOffered;
        assertEquals(0.5, collapseFraction, 1e-6,
                "the deficit above the crossover IS the collapse fraction - F2's story in one line");
    }

    @Test
    @DisplayName("the crossover is the smallest N with offered rho < 1")
    void crossoverIsSmallestStableN() {
        assertEquals(8, QueueingRho.crossover(cusFor(5.5), WINDOW, withReconcilers(1), LADDER),
                "5.5 servers' worth: N=4 is over, N=8 is the first under");
        assertEquals(1, QueueingRho.crossover(cusFor(0.4), WINDOW, withReconcilers(1), LADDER));
        assertEquals(-1, QueueingRho.crossover(cusFor(40.0), WINDOW, withReconcilers(1), LADDER),
                "beyond the ladder - reported as absent, not clamped to its top");
    }

    @Test
    @DisplayName("an unevaluable cell fails rather than passes")
    void unevaluableIsNotStable() {
        assertTrue(Double.isNaN(QueueingRho.rho(100, 0, withReconcilers(4))), "zero window");
        assertTrue(Double.isNaN(QueueingRho.rho(100, 1000, withReconcilers(0))), "no reconcilers");
        assertFalse(QueueingRho.stable(Double.NaN));
    }
}
