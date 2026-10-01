package test.java.experiments.support;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import de.tum.tssimulator.experiments.support.ExperimentOutputPaths;

class ExperimentOutputPathsTest {
    private static final String OUTPUT_DIR_PROPERTY = "ts.experiments.outputDir";

    @AfterEach
    void tearDown() {
        System.clearProperty(OUTPUT_DIR_PROPERTY);
    }

    @Test
    void usesDedicatedArtifactsDirectoryByDefault() {
        String path = ExperimentOutputPaths.resultsFile("sample.csv");
        assertTrue(path.contains("artifacts"));
        assertTrue(path.contains("experiments"));
        assertTrue(path.contains("results"));
    }

    @Test
    void allowsExplicitOverrideForLegacyOrCustomWorkflows() {
        System.setProperty(OUTPUT_DIR_PROPERTY, "data/results");
        String path = ExperimentOutputPaths.resultsFile("sample.csv");
        assertTrue(path.contains("data"));
        assertTrue(path.contains("results"));
    }
}

