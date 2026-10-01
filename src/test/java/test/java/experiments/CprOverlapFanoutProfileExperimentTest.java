package test.java.experiments;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import de.tum.tssimulator.experiments.CprOverlapFanoutProfileExperiment.FanoutProfile;
import de.tum.tssimulator.experiments.CprOverlapFanoutProfileExperiment;

class CprOverlapFanoutProfileExperimentTest {

    @Test
    void exposesRequestedModelCounts() {
        assertArrayEquals(new int[] {5, 10, 30}, CprOverlapFanoutProfileExperiment.modelCounts());
    }

    @Test
    void exposesRequestedSemanticOverlaps() {
        assertArrayEquals(new double[] {0.00, 0.05, 0.10, 0.20, 0.30, 0.50},
                CprOverlapFanoutProfileExperiment.semanticOverlaps());
    }

    @Test
    void exposesLineAndWiderFanoutProfiles() {
        FanoutProfile[] profiles = CprOverlapFanoutProfileExperiment.fanoutProfiles();
        assertEquals(5, profiles.length);
        assertEquals("line", profiles[0].name());
        assertEquals(1, profiles[0].maxCprsPerResource());
        assertEquals(1.0, profiles[0].avgCprsPerResource());
        assertEquals("very_heavy", profiles[4].name());
        assertEquals(10, profiles[4].maxCprsPerResource());
        assertEquals(8.0, profiles[4].avgCprsPerResource());
    }

    @Test
    void countsAllProfileOverlapModelCombinations() {
        assertEquals(90, CprOverlapFanoutProfileExperiment.uniqueRunCount());
    }
}
