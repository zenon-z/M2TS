package de.tum.tssimulator.cli;

import java.util.LinkedHashMap;
import java.util.Map;

public record CliOptions(Command command, String configPath, String profile, Map<String, String> overrides) {
    public CliOptions {
        overrides = Map.copyOf(new LinkedHashMap<>(overrides));
    }

    public CliOptions(Command command, String configPath) {
        this(command, configPath, null, Map.of());
    }

    public enum Command {
        RUN,
        HELP,
        VERSION
    }
}
