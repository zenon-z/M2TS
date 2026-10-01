package test.java.experiments.variation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import de.tum.tssimulator.config.SimulatorParameters;
import de.tum.tssimulator.experiments.variation.VaryAvgCprsPerResource;
import de.tum.tssimulator.experiments.variation.VaryCprFraction;
import de.tum.tssimulator.experiments.variation.VaryMaxCprsPerResource;

class CprParameterVariationTest {

    @Test
    void cprVariationsPreserveUnrelatedParameters() {
        SimulatorParameters base = SimulatorParameters.builder()
                .totalModels(7)
                .totalResources(700)
                .useRandomResourceSelection(true)
                .semanticOverlap(0.20)
                .maxCprsPerResource(5)
                .avgCprsPerResource(2.0)
                .transactionSamplingSeed(11L)
                .semanticOverlapSeed(22L)
                .build();

        SimulatorParameters fraction = new VaryCprFraction(0.40).applyVariation(base, 0.40);
        assertEquals(0.40, fraction.semanticOverlap);
        assertEquals(5, fraction.maxCprsPerResource);
        assertEquals(2.0, fraction.avgCprsPerResource);
        assertUnchangedBaseFields(base, fraction);

        SimulatorParameters max = new VaryMaxCprsPerResource(8).applyVariation(base, 8);
        assertEquals(0.20, max.semanticOverlap);
        assertEquals(8, max.maxCprsPerResource);
        assertEquals(2.0, max.avgCprsPerResource);
        assertUnchangedBaseFields(base, max);

        SimulatorParameters avg = new VaryAvgCprsPerResource(3.5).applyVariation(base, 3.5);
        assertEquals(0.20, avg.semanticOverlap);
        assertEquals(5, avg.maxCprsPerResource);
        assertEquals(3.5, avg.avgCprsPerResource);
        assertUnchangedBaseFields(base, avg);
    }

    private static void assertUnchangedBaseFields(SimulatorParameters base, SimulatorParameters varied) {
        assertEquals(base.totalModels, varied.totalModels);
        assertEquals(base.totalResources, varied.totalResources);
        assertEquals(base.useRandomResourceSelection, varied.useRandomResourceSelection);
        assertEquals(base.transactionSamplingSeed, varied.transactionSamplingSeed);
        assertEquals(base.semanticOverlapSeed, varied.semanticOverlapSeed);
    }
}
