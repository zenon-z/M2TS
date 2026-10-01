package test.java.experiments;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import de.tum.tssimulator.experiments.CprTopologyComparisonExperiment.FanoutSetting;
import de.tum.tssimulator.experiments.CprTopologyComparisonExperiment;

class CprTopologyComparisonExperimentTest {

    @Test
    void exposesRequestedOverlapSweep() {
        assertArrayEquals(new double[] {0.00, 0.05, 0.10, 0.20, 0.30, 0.50},
                CprTopologyComparisonExperiment.semanticOverlaps());
    }

    @Test
    void exposesRequestedFanoutSettings() {
        FanoutSetting[] settings = CprTopologyComparisonExperiment.fanoutSettings();
        assertEquals(5, settings.length);
        assertEquals("avg1_max3", settings[0].label());
        assertEquals(1.0, settings[0].avgCprsPerResource());
        assertEquals(3, settings[0].maxCprsPerResource());
        assertEquals("avg2_max5", settings[1].label());
        assertEquals(2.0, settings[1].avgCprsPerResource());
        assertEquals(5, settings[1].maxCprsPerResource());
        assertEquals("avg3_max6", settings[2].label());
        assertEquals(3.0, settings[2].avgCprsPerResource());
        assertEquals(6, settings[2].maxCprsPerResource());
        assertEquals("avg4_max10", settings[3].label());
        assertEquals(4.0, settings[3].avgCprsPerResource());
        assertEquals(10, settings[3].maxCprsPerResource());
        assertEquals("avg5_max10", settings[4].label());
        assertEquals(5.0, settings[4].avgCprsPerResource());
        assertEquals(10, settings[4].maxCprsPerResource());
    }

    @Test
    void exposesRequestedProfiles() {
        assertArrayEquals(new String[] {"uniform_line", "uniform_non_line", "hotspot", "ring"},
                CprTopologyComparisonExperiment.profileNames());
    }

    @Test
    void countsAllProfileOverlapCombinations() {
        assertEquals(120, CprTopologyComparisonExperiment.uniqueRunCount());
    }
}
