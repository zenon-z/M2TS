package de.tum.tssimulator.bootstrap;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

import de.tum.tssimulator.cli.CliOptions;
import de.tum.tssimulator.config.SimulatorParameters;
import de.tum.tssimulator.config.SimulatorParametersPropertyLoader;

public final class SimulatorConfigurationLoader {
    private static final String DEFAULT_RESOURCE = "simulator.properties";
    private static final String PROFILE_ENVIRONMENT_VARIABLE = "TS_SIMULATOR_PROFILE";

    private SimulatorConfigurationLoader() {
    }

    public static ResolvedSimulatorConfiguration load(CliOptions options) {
        return load(options, System.getenv());
    }

    public static ResolvedSimulatorConfiguration load(CliOptions options, Map<String, String> environment) {
        try {
            SimulatorParameters.Builder builder = SimulatorParameters.builder();
            Map<String, String> effectiveProperties = new LinkedHashMap<>();

            applyLayer(builder, effectiveProperties,
                    toPlainMap(SimulatorParametersPropertyLoader.loadResourceProperties(DEFAULT_RESOURCE)),
                    "resource " + DEFAULT_RESOURCE);

            String configSource = "resource " + DEFAULT_RESOURCE;
            if (options.configPath() != null && !options.configPath().isBlank()) {
                applyLayer(builder, effectiveProperties,
                        toPlainMap(SimulatorParametersPropertyLoader.loadFileProperties(options.configPath())),
                        "file " + options.configPath());
                configSource = "file " + options.configPath();
            }

            applyLayer(builder, effectiveProperties,
                    SimulatorParametersPropertyLoader.extractEnvironmentOverrides(environment),
                    "environment");
            applyLayer(builder, effectiveProperties, options.overrides(), "command line");

            return new ResolvedSimulatorConfiguration(
                    builder.build(),
                    resolveProfile(options, environment),
                    configSource,
                    effectiveProperties);
        } catch (IOException | IllegalArgumentException e) {
            throw new SimulatorConfigurationException("Failed to load simulator configuration", e);
        }
    }

    private static void applyLayer(SimulatorParameters.Builder builder, Map<String, String> effectiveProperties,
            Map<String, String> properties, String sourceDescription) {
        if (properties.isEmpty()) {
            return;
        }
        SimulatorParametersPropertyLoader.applyProperties(builder, properties, sourceDescription);
        properties.forEach((key, value) -> effectiveProperties.put(normalizeLoggedKey(key), value));
    }

    private static Map<String, String> toPlainMap(Properties properties) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String propertyName : properties.stringPropertyNames()) {
            values.put(propertyName, properties.getProperty(propertyName));
        }
        return values;
    }

    private static String resolveProfile(CliOptions options, Map<String, String> environment) {
        if (options.profile() != null && !options.profile().isBlank()) {
            return options.profile();
        }
        String environmentProfile = environment.get(PROFILE_ENVIRONMENT_VARIABLE);
        if (environmentProfile != null && !environmentProfile.isBlank()) {
            return environmentProfile.trim();
        }
        return "default";
    }

    private static String normalizeLoggedKey(String key) {
        if (key.startsWith("simulator.")) {
            return key.substring("simulator.".length());
        }
        return key;
    }
}
