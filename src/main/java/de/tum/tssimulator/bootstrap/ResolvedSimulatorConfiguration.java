package de.tum.tssimulator.bootstrap;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.StringJoiner;

import de.tum.tssimulator.config.SimulatorParameters;

public record ResolvedSimulatorConfiguration(
        SimulatorParameters parameters,
        String profile,
        String configSource,
        Map<String, String> effectiveProperties) {
    public ResolvedSimulatorConfiguration {
        effectiveProperties = Map.copyOf(new LinkedHashMap<>(effectiveProperties));
        profile = profile == null || profile.isBlank() ? "default" : profile;
    }

    public String describe() {
        StringJoiner joiner = new StringJoiner(", ", "{", "}");
        effectiveProperties.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> joiner.add(entry.getKey() + "=" + entry.getValue()));
        return "profile=" + profile + ", source=" + configSource + ", effectiveProperties=" + joiner;
    }
}
