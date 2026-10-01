package test.java.experiments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import de.tum.tssimulator.config.ConcurrencyModel;
import de.tum.tssimulator.config.SimulatorParameters;
import de.tum.tssimulator.experiments.CprParameterAcidThroughputExperiment.CprSweep;
import de.tum.tssimulator.experiments.CprParameterAcidThroughputExperiment;

class CprParameterAcidThroughputExperimentTest {

    @Test
    void baseConfigurationRunsAcidAndUsesValidCprFanOutBaseline() {
        SimulatorParameters base = CprParameterAcidThroughputExperiment.baseParameters();

        assertEquals(ConcurrencyModel.ACID, base.concurrencyModel);
        assertEquals(0.10, base.semanticOverlap);
        assertEquals(5, base.maxCprsPerResource);
        assertEquals(2.0, base.avgCprsPerResource);
    }

    @Test
    void sweepsVaryExactlyTheThreeCprParametersOneAtATime() {
        SimulatorParameters base = CprParameterAcidThroughputExperiment.baseParameters();
        CprSweep[] sweeps = CprParameterAcidThroughputExperiment.sweeps();

        assertEquals(3, sweeps.length);
        assertEquals("cprFraction", sweeps[0].parameterName());
        assertEquals("maxCprsPerResource", sweeps[1].parameterName());
        assertEquals("avgCprsPerResource", sweeps[2].parameterName());

        for (Object value : sweeps[0].variation().getValues()) {
            SimulatorParameters varied = sweeps[0].variation().applyVariation(base, value);
            assertEquals(value, varied.semanticOverlap);
            assertEquals(base.maxCprsPerResource, varied.maxCprsPerResource);
            assertEquals(base.avgCprsPerResource, varied.avgCprsPerResource);
            assertEquals(base.concurrencyModel, varied.concurrencyModel);
        }

        for (Object value : sweeps[1].variation().getValues()) {
            SimulatorParameters varied = sweeps[1].variation().applyVariation(base, value);
            assertEquals(base.semanticOverlap, varied.semanticOverlap);
            assertEquals(value, varied.maxCprsPerResource);
            assertEquals(base.avgCprsPerResource, varied.avgCprsPerResource);
            assertTrue(varied.avgCprsPerResource <= varied.maxCprsPerResource);
            assertEquals(base.concurrencyModel, varied.concurrencyModel);
        }

        for (Object value : sweeps[2].variation().getValues()) {
            SimulatorParameters varied = sweeps[2].variation().applyVariation(base, value);
            assertEquals(base.semanticOverlap, varied.semanticOverlap);
            assertEquals(base.maxCprsPerResource, varied.maxCprsPerResource);
            assertEquals(value, varied.avgCprsPerResource);
            assertTrue(varied.avgCprsPerResource <= varied.maxCprsPerResource);
            assertEquals(base.concurrencyModel, varied.concurrencyModel);
        }
    }
}
