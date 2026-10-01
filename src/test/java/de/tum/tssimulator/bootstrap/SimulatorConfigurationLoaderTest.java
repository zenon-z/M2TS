package de.tum.tssimulator.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;

import de.tum.tssimulator.cli.CliOptions;

class SimulatorConfigurationLoaderTest {

    @Test
    void layeredConfigUsesCliThenEnvironmentThenFileThenBundledDefaults() throws Exception {
        Path propsFile = Files.createTempFile("simulator-layered", ".properties");
        Files.writeString(propsFile, """
                totalModels=2
                totalResources=25
                numberOfIoProcessors=2
                totalLocks=25
                """);

        CliOptions options = new CliOptions(
                CliOptions.Command.RUN,
                propsFile.toString(),
                "production",
                Map.of(
                        "totalModels", "4",
                        "stepTimeSlice", "5"));

        ResolvedSimulatorConfiguration resolved = SimulatorConfigurationLoader.load(options, Map.of(
                "TS_SIMULATOR_TOTAL_RESOURCES", "30",
                "TS_SIMULATOR_NUMBER_OF_IO_PROCESSORS", "6",
                "TS_SIMULATOR_PROFILE", "staging"));

        assertEquals("production", resolved.profile());
        assertEquals(4, resolved.parameters().totalModels);
        assertEquals(30, resolved.parameters().totalResources);
        assertEquals(6, resolved.parameters().numberOfIoProcessors);
        assertEquals(25, resolved.parameters().totalLocks);
        assertEquals(5L, resolved.parameters().stepTimeSlice);
    }

    @Test
    void defaultsToEnvironmentProfileWhenCliProfileIsAbsent() {
        ResolvedSimulatorConfiguration resolved = SimulatorConfigurationLoader.load(
                new CliOptions(CliOptions.Command.RUN, null, null, Map.of()),
                Map.of("TS_SIMULATOR_PROFILE", "test"));

        assertEquals("test", resolved.profile());
    }

    @Test
    void describeIncludesProfileSourceAndEffectiveValues() {
        ResolvedSimulatorConfiguration resolved = new ResolvedSimulatorConfiguration(
                null,
                "production",
                "file config.properties",
                Map.of("totalModels", "5", "totalResources", "25"));

        String description = resolved.describe();

        assertTrue(description.contains("profile=production"));
        assertTrue(description.contains("source=file config.properties"));
        assertTrue(description.contains("totalModels=5"));
    }
}
