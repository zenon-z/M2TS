package de.tum.tssimulator.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CliArgumentParserTest {

    @Test
    void defaultsToRunWithBundledConfig() {
        CliOptions options = CliArgumentParser.parse(new String[0]);
        assertEquals(CliOptions.Command.RUN, options.command());
        assertEquals(null, options.configPath());
        assertEquals(null, options.profile());
        assertTrue(options.overrides().isEmpty());
    }

    @Test
    void supportsLegacyPositionalConfigPath() {
        CliOptions options = CliArgumentParser.parse(new String[] { "custom.properties" });
        assertEquals(CliOptions.Command.RUN, options.command());
        assertEquals("custom.properties", options.configPath());
    }

    @Test
    void supportsExplicitRunCommandAndConfigFlag() {
        CliOptions options = CliArgumentParser.parse(new String[] { "run", "--config", "custom.properties" });
        assertEquals(CliOptions.Command.RUN, options.command());
        assertEquals("custom.properties", options.configPath());
    }

    @Test
    void supportsProfileAndPropertyOverrides() {
        CliOptions options = CliArgumentParser.parse(
                new String[] { "run", "--profile", "production", "--set", "totalModels=5", "--set", "numTerminals=7" });
        assertEquals(CliOptions.Command.RUN, options.command());
        assertEquals("production", options.profile());
        assertEquals("5", options.overrides().get("totalModels"));
        assertEquals("7", options.overrides().get("numTerminals"));
    }

    @Test
    void supportsLegacyConfigPathWithAdditionalFlags() {
        CliOptions options = CliArgumentParser.parse(
                new String[] { "custom.properties", "--profile", "staging", "--set", "totalModels=4" });
        assertEquals(CliOptions.Command.RUN, options.command());
        assertEquals("custom.properties", options.configPath());
        assertEquals("staging", options.profile());
        assertEquals("4", options.overrides().get("totalModels"));
    }

    @Test
    void supportsHelpAndVersionCommands() {
        assertEquals(CliOptions.Command.HELP, CliArgumentParser.parse(new String[] { "help" }).command());
        assertEquals(CliOptions.Command.VERSION, CliArgumentParser.parse(new String[] { "version" }).command());
    }

    @Test
    void rejectsUnknownFlags() {
        assertThrows(IllegalArgumentException.class, () -> CliArgumentParser.parse(new String[] { "--wat" }));
    }

    @Test
    void rejectsInvalidOverrideSyntax() {
        assertThrows(IllegalArgumentException.class,
                () -> CliArgumentParser.parse(new String[] { "run", "--set", "totalModels" }));
    }
}
