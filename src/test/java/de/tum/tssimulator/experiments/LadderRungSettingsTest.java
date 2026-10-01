package de.tum.tssimulator.experiments;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import de.tum.tssimulator.config.SimulatorParameters;
import de.tum.tssimulator.experiments.DynamicFootprintExperiment.Rung;

/**
 * A ladder rung must carry the settings its name promises.
 *
 * <p>The rung used to be a filename tag only - {@code operationBatchSize}, {@code writeFraction} and
 * {@code writeOnlyCprSources} were never applied, so running "rung1" re-ran rung 0 under a different
 * name. That failure is invisible in the worst way available: criterion 2 asserts C2PL is IDENTICAL
 * between rungs 0 and 1, and an inert rung 1 satisfies it perfectly for BOTH protocols. The
 * conclusion drawn would have been "the cursor is correctly gated behind LockingProtocol" from a run
 * in which nothing was gated because nothing was switched on - the same signature as the other
 * defects on this work, where the broken version produces the result the hypothesis wants.
 *
 * <p>These tests are the entire verification that a rung is what it claims. The ladder itself cannot
 * provide it: an inert rung produces a plausible, internally-consistent CSV.
 */
class LadderRungSettingsTest {

    @Test
    void eachRungCarriesTheSettingsItsNameMeans() {
        assertEquals(List.of(
                "operationBatchSize=0", "writeFraction=1.0", "writeOnlyCprSources=true"),
                Rung.RUNG0.settingsDescription(),
                "rung 0 is the anchor: whole-footprint acquisition, all-WRITE");
        assertEquals(List.of(
                "operationBatchSize=1", "writeFraction=1.0", "writeOnlyCprSources=true"),
                Rung.RUNG1.settingsDescription(),
                "rung 1 differs from rung 0 in acquisition timing ONLY");
        assertEquals(List.of(
                "operationBatchSize=1", "writeFraction=0.5", "writeOnlyCprSources=false"),
                Rung.RUNG2.settingsDescription(),
                "rung 2 adds mixed modes with CPR fan-out held at full width");
        assertEquals(List.of(
                "operationBatchSize=1", "writeFraction=0.5", "writeOnlyCprSources=true"),
                Rung.RUNG3.settingsDescription(),
                "rung 3 adds write-only sourcing - the one variable between it and rung 2");
    }

    /**
     * The property rung-0 bit-identity rests on, asserted rather than assumed: applying rung 0
     * changes nothing, because every one of its settings already IS the Builder default. Without
     * this, "rung 0 reproduced the baseline" would be a coincidence to re-verify by running the
     * experiment; with it, it is structural.
     */
    @Test
    void rungZeroIsAStructuralNoOp() {
        SimulatorParameters defaults = SimulatorParameters.builder().build();
        SimulatorParameters applied = Rung.RUNG0.apply(defaults);

        assertEquals(defaults.operationBatchSize, applied.operationBatchSize);
        assertEquals(defaults.writeFraction, applied.writeFraction);
        assertEquals(defaults.writeOnlyCprSources, applied.writeOnlyCprSources);
        assertEquals(0, applied.operationBatchSize, "the cursor must be disengaged at rung 0");
        assertEquals(1.0, applied.writeFraction, 1e-12, "all-WRITE at rung 0");
    }

    /** Exactly one setting separates consecutive rungs, so a movement has one candidate cause. */
    @Test
    void consecutiveRungsDifferInExactlyOneSetting() {
        assertEquals(1, differences(Rung.RUNG0, Rung.RUNG1),
                "rungs 0->1 must vary acquisition timing alone, or a hold-time change has two causes");
        assertEquals(1, differences(Rung.RUNG2, Rung.RUNG3),
                "rungs 2->3 must vary CPR sourcing alone");
        // 1 -> 2 is the deliberate exception: writeFraction and writeOnlyCprSources move together,
        // because holding fan-out fixed while introducing reads is itself what isolates S/X.
        assertEquals(2, differences(Rung.RUNG1, Rung.RUNG2));
    }

    private static int differences(Rung a, Rung b) {
        int differing = 0;
        for (int i = 0; i < a.settingsDescription().size(); i++) {
            if (!a.settingsDescription().get(i).equals(b.settingsDescription().get(i))) {
                differing++;
            }
        }
        return differing;
    }

    @Test
    void applyingARungActuallyChangesTheParameters() {
        SimulatorParameters base = SimulatorParameters.builder().build();

        assertEquals(1, Rung.RUNG1.apply(base).operationBatchSize,
                "rung 1 must engage the cursor - an inert rung 1 makes criterion 2 vacuous");
        assertEquals(0.5, Rung.RUNG2.apply(base).writeFraction, 1e-12);
        assertTrue(!Rung.RUNG2.apply(base).writeOnlyCprSources);
        assertTrue(Rung.RUNG3.apply(base).writeOnlyCprSources);
    }

    /**
     * An unknown label is rejected rather than accepted as a bare filename tag. A label with no
     * settings behind it would run the previous rung's configuration under a new name and produce a
     * CSV nothing about the output could distinguish from a real one.
     */
    @Test
    void anUndefinedRungLabelIsRejected() {
        IllegalArgumentException error =
                assertThrows(IllegalArgumentException.class, () -> Rung.forLabel("rung4"));
        assertTrue(error.getMessage().contains("rung0"),
                "the error must name the valid rungs, not just refuse");

        assertThrows(IllegalArgumentException.class, () -> Rung.forLabel("baseline"));
        assertThrows(IllegalArgumentException.class, () -> Rung.forLabel(""));
    }

    @Test
    void rungLabelsAreCaseInsensitive() {
        assertEquals(Rung.RUNG0, Rung.forLabel("RUNG0"));
        assertEquals(Rung.RUNG2, Rung.forLabel("Rung2"));
    }
}
