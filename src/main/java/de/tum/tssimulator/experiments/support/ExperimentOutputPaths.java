package de.tum.tssimulator.experiments.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Centralizes experiment output location policy so individual experiments do not hard-code filesystem roots.
 */
public final class ExperimentOutputPaths {
    private static final String OUTPUT_DIR_PROPERTY = "ts.experiments.outputDir";
    private static final String DEFAULT_OUTPUT_DIR = "artifacts/experiments/results";

    private ExperimentOutputPaths() {
    }

    public static String resultsFile(String fileName) {
        Path output = outputRoot().resolve(fileName);
        try {
            Files.createDirectories(output.getParent());
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to prepare experiment output directory for " + output, e);
        }
        return output.toString();
    }

    private static Path outputRoot() {
        return Path.of(System.getProperty(OUTPUT_DIR_PROPERTY, DEFAULT_OUTPUT_DIR));
    }
}

