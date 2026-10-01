package test.java.concurrency;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import de.tum.tssimulator.config.ConcurrencyModel;
import de.tum.tssimulator.config.SimulatorParameters;
import de.tum.tssimulator.experiments.SimulationRunner.SimulationRunOutcome;
import de.tum.tssimulator.experiments.support.BaseExperimentalDesignDefaults;
import de.tum.tssimulator.experiments.support.SeedSweepRunner;

/**
 * {@code EQUILIBRIUM} holds the in-flight population at {@code initialTransactions} by replacing
 * each completed transaction. A run longer than it takes to drain the initial set must therefore
 * complete MORE groups than the population size.
 *
 * <p><b>The precondition had nothing enforcing it for BASE.</b> This is C25: "EQUILIBRIUM sustains
 * the population" was relied on by every BASE plan - F2's reconciler crossover, F3's ACID/BASE phase
 * diagram, V3's outbox arm - and no test asserted it. The suite was green at 459 tests with
 * replacement broken for BASE, which is itself the finding.
 *
 * <p>Both models are asserted so the pair is symmetric: if ACID ever regresses the same way, this
 * catches it rather than leaving BASE looking uniquely broken.
 *
 * <p><b>There is deliberately NO test that ACID's parent commits equal its group completions.</b>
 * One was written and removed: {@code incrementGroupsCompleted()} bumps both counters in a single
 * call at every ACID site, so the equality holds by PLUMBING and the assertion could not fail -
 * the shape. The coincidence is real (an ACID parent moveToWaitingRoom()s and completes only with
 * its group) but it is true BY CONSTRUCTION here, not by measurement. Making it a measured fact
 * needs ACID's parent counter moved to where the parent actually leaves the producer population -
 * {@code lifecycle.complete(parent)} on waiting-room release - which is a change to a working ACID
 * path and is not made for the sake of an assertion.
 */
class EquilibriumPopulationReplacementTest {

    /** Long enough that the initial set drains and the stall becomes visible - the watchdog trips
     *  100 idle steps after the last completion, so a 60 s run ends before it fires. */
    private static final int DURATION_SECONDS = 240;

    @Test
    @DisplayName("ACID replaces completed transactions under EQUILIBRIUM")
    void acidSustainsThePopulation() {
        assertReplaces(ConcurrencyModel.ACID);
    }

    @Test
    @DisplayName("BASE replaces completed transactions under EQUILIBRIUM")
    void baseSustainsThePopulation() {
        assertReplaces(ConcurrencyModel.BASE);
    }

    private static void assertReplaces(ConcurrencyModel model) {
        SimulatorParameters params = SeedSweepRunner
                .copyBuilder(BaseExperimentalDesignDefaults.baseParameters())
                .concurrencyModel(model)
                .simulationDurationSeconds(DURATION_SECONDS)
                .build();

        List<SimulationRunOutcome> outcomes =
                new SeedSweepRunner().runRaw("equilibrium-" + model, params, new long[] {1_000_003L});

        assertFalse(outcomes.isEmpty(),
                model + ": the run hit the watchdog. Under EQUILIBRIUM the population is held at "
                + "initialTransactions by replacement, so the engine should never run dry - a "
                + "watchdog here means completed transactions are not being replaced.");

        int groups = outcomes.get(0).snapshot().groupsCompletedTotal();
        assertTrue(groups > params.initialTransactions,
                model + ": completed " + groups + " groups against initialTransactions="
                + params.initialTransactions + " over " + DURATION_SECONDS + " s. Equal means the "
                + "initial population ran once and nothing replaced it, i.e. the run measured a "
                + "TRANSIENT rather than a steady state.");
    }
}
