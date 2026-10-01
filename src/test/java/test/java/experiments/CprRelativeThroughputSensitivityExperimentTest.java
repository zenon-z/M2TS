package test.java.experiments;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import de.tum.tssimulator.experiments.CprRelativeThroughputSensitivityExperiment;

class CprRelativeThroughputSensitivityExperimentTest {

    @Test
    void exposesRequestedModelCounts() {
        assertArrayEquals(new int[] {5, 10, 30}, CprRelativeThroughputSensitivityExperiment.modelCounts());
    }

    @Test
    void countsBaselineAndAllFourSweeps() {
        assertEquals(67, CprRelativeThroughputSensitivityExperiment.uniqueRunCount());
    }

    @Test
    void exposesRequestedCprFanOutValues() {
        assertArrayEquals(
                new double[] {1.0, 2.0, 4.0, 6.0, 8.0},
                CprRelativeThroughputSensitivityExperiment.avgCprsPerResourceValues());
        assertArrayEquals(
                new int[] {2, 3, 5, 8, 10},
                CprRelativeThroughputSensitivityExperiment.maxCprsPerResourceValues());
    }

    @Test
    void usesThirtyPercentSemanticOverlapForFixedOverlapSweeps() {
        assertEquals(0.30, CprRelativeThroughputSensitivityExperiment.fixedSemanticOverlap());
    }
}
