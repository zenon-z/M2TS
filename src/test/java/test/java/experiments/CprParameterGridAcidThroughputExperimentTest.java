package test.java.experiments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import de.tum.tssimulator.config.SimulatorParameters;
import de.tum.tssimulator.experiments.CprParameterAcidThroughputExperiment;
import de.tum.tssimulator.experiments.CprParameterGridAcidThroughputExperiment;

class CprParameterGridAcidThroughputExperimentTest {

    @Test
    void gridSkipsInvalidAverageGreaterThanMaxCombinations() {
        assertEquals(102, CprParameterGridAcidThroughputExperiment.uniqueParameterCombinationCount());
        assertEquals(510, CprParameterGridAcidThroughputExperiment.validCombinationCount());
        assertEquals(5, CprParameterGridAcidThroughputExperiment.cprSeeds().length);
    }

    @Test
    void gridParameterFactorySetsAllThreeCprParameters() {
        SimulatorParameters base = CprParameterAcidThroughputExperiment.baseParameters();
        SimulatorParameters params = CprParameterGridAcidThroughputExperiment.createParameters(
                base, 0.30, 8, 4.0);

        assertEquals(0.30, params.semanticOverlap);
        assertEquals(8, params.maxCprsPerResource);
        assertEquals(4.0, params.avgCprsPerResource);
        assertTrue(params.avgCprsPerResource <= params.maxCprsPerResource);
        assertEquals(base.concurrencyModel, params.concurrencyModel);
    }

    @Test
    void gridParameterFactoryCanOverrideOnlyCprSeed() {
        SimulatorParameters base = CprParameterAcidThroughputExperiment.baseParameters();
        SimulatorParameters params = CprParameterGridAcidThroughputExperiment.createParameters(
                base, 0.30, 8, 4.0, 999L);

        assertEquals(999L, params.semanticOverlapSeed);
        assertEquals(base.transactionSamplingSeed, params.transactionSamplingSeed);
        assertEquals(0.30, params.semanticOverlap);
        assertEquals(8, params.maxCprsPerResource);
        assertEquals(4.0, params.avgCprsPerResource);
    }
}
