package test.java.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import de.tum.tssimulator.cli.Main;
import de.tum.tssimulator.cli.StartupExitCode;

class MainCliContractTest {

    @Test
    void helpCommandExitsSuccessfully() {
        assertEquals(StartupExitCode.SUCCESS.code(), Main.run(new String[] { "help" }));
    }

    @Test
    void versionCommandExitsSuccessfully() {
        assertEquals(StartupExitCode.SUCCESS.code(), Main.run(new String[] { "version" }));
    }

    @Test
    void invalidCliArgumentsUseConfigErrorExitCode() {
        assertEquals(StartupExitCode.CONFIG_ERROR.code(), Main.run(new String[] { "--unknown" }));
    }
}

