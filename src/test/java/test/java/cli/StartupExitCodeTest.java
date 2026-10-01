package test.java.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import de.tum.tssimulator.bootstrap.SimulationWatchdogException;
import de.tum.tssimulator.bootstrap.SimulatorConfigurationException;
import de.tum.tssimulator.cli.Main;
import de.tum.tssimulator.cli.StartupExitCode;

class StartupExitCodeTest {

    @Test
    void mapsConfigurationFailuresToConfigExitCode() throws Exception {
        Path configFile = Files.createTempFile("ts-simulator-invalid-exit-code", ".properties");
        Files.writeString(configFile, """
                totalModels=not-a-number
                totalResources=25
                """);

        int exitCode = Main.run(new String[] { configFile.toString() });

        assertEquals(StartupExitCode.CONFIG_ERROR.code(), exitCode);
    }

    @Test
    void classifiesWatchdogFailuresSeparatelyFromGenericRuntimeFailures() {
        assertEquals(
                StartupExitCode.WATCHDOG_FAILURE,
                StartupExitCode.fromFailure(new SimulationWatchdogException("watchdog tripped")));
        assertEquals(
                StartupExitCode.RUNTIME_ERROR,
                StartupExitCode.fromFailure(new IllegalStateException("generic runtime failure")));
        assertEquals(
                StartupExitCode.CONFIG_ERROR,
                StartupExitCode.fromFailure(new SimulatorConfigurationException("bad config", new IllegalArgumentException())));
    }
}
