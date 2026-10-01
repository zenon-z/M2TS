package test.java.experiments.support;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

import de.tum.tssimulator.config.SimulatorParameters;
import de.tum.tssimulator.experiments.support.EffectiveConfigHasher;

/**
 * {@link EffectiveConfigHasher} must (a) produce the SAME hash for two configs
 * that differ only in the two per-seed RNG seed fields (that's what a seed sweep does - rows
 * sharing a config_id are expected to differ there), and (b) produce a DIFFERENT hash when any
 * config-defining field differs (the actual regression this class exists to catch - see
 * SeedSweepRunnerCopyBuilderRoundTripTest for the incident).
 */
class EffectiveConfigHasherTest {

    private SimulatorParameters.Builder baseBuilder() {
        return SimulatorParameters.builder()
                .totalModels(5)
                .propertiesPerModel(List.of(4, 4, 4, 4, 4))
                .instancePoolSize(20)
                .semanticOverlap(0.3)
                .avgCprsPerResource(2.0)
                .maxCprsPerResource(4);
    }

    @Test
    void sameHashAcrossSeedsOfSameConfig() {
        SimulatorParameters seed1 = baseBuilder()
                .transactionSamplingSeed(111L).semanticOverlapSeed(111L).build();
        SimulatorParameters seed2 = baseBuilder()
                .transactionSamplingSeed(222L).semanticOverlapSeed(222L).build();

        assertEquals(EffectiveConfigHasher.hash(seed1), EffectiveConfigHasher.hash(seed2),
                "rows from the same config cell must share a config_hash regardless of seed");
    }

    @Test
    void differentHashWhenConfigDefiningFieldDiffers() {
        SimulatorParameters original = baseBuilder()
                .transactionSamplingSeed(111L).semanticOverlapSeed(111L).build();
        SimulatorParameters differentInstancePool = baseBuilder()
                .instancePoolSize(21) // the exact field the copyBuilder bug silently dropped
                .transactionSamplingSeed(111L).semanticOverlapSeed(111L).build();

        assertNotEquals(EffectiveConfigHasher.hash(original), EffectiveConfigHasher.hash(differentInstancePool),
                "a real config difference must change the hash even if seeds match");
    }
}
