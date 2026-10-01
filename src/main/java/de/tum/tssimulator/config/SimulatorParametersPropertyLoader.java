package de.tum.tssimulator.config;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import de.tum.tssimulator.bookkeeping.CprTopologyMode;
import de.tum.tssimulator.bookkeeping.ReferentialAction;

/**
 * Loads SimulatorParameters from a Java properties file.
 * Handles property parsing, type conversion, and validation.
 * Supports both prefixed ("simulator.*") and non-prefixed property keys.
 */
public class SimulatorParametersPropertyLoader {
    private static final String RESOURCE_PREFIX = "simulator.";
    private static final String ENVIRONMENT_PREFIX = "TS_SIMULATOR_";
    private static final Set<String> SUPPORTED_KEYS = Set.of(
            "totalModels",
            "totalResources",
            "propertiesPerModel",
            "instancePoolSize",
            "ioTimePerResource",
            "cpuTimePerResource",
            "lockIOTimePerResource",
            "lockCPUTimePerResource",
            "initialTransactions",
            "totalTransactionSteps",
            "maxTransactionModels",
            "maxTransactionResources",
            "useConstantTransactionSize",
            "constantTransactionSize",
            "transactionSizeProfile",
            "smallTransactionSize",
            "largeTransactionSize",
            "largeTransactionProbability",
            "useRandomResourceSelection",
            "accessDistribution",
            "zipfTheta",
            "backoffScale",
            "workloadMix",
            "readOnlyTransactionFraction",
            "semanticOverlap",
            "maxCprsPerResource",
            "avgCprsPerResource",
            "cprTopologyMode",
            "referentialAction",
            "concurrencyModel",
            "simulationDurationSeconds",
            "numberOfIoProcessors",
            "numberOfCpuProcessors",
            "numberOfReconcilerProcessors",
            "totalLocks",
            "transactionSamplingSeed",
            "semanticOverlapSeed",
            "stepTimeSlice",
            "transactionMode",
            "numTerminals",
            "meanInterArrivalDelay",
            "transactionGenerationMode");

    private static final Set<String> SIZE_KEYS_IGNORED_BY_PROGRAMS_MODE = Set.of(
            "transactionSizeProfile",
            "maxTransactionResources",
            "useConstantTransactionSize",
            // Deprecated in TRANSACTION_PROGRAMS mode: once every variable's model is fixed by
            // its declared property, there's no round-robin model draw left for it to govern.
            "maxTransactionModels");

    public static SimulatorParameters fromResourcesFile(String filename) throws IOException {
        return fromProperties(loadResourceProperties(filename), "resource " + filename);
    }

    public static SimulatorParameters fromPropertiesFile(String filename) throws IOException {
        return fromProperties(loadFileProperties(filename), "file " + filename);
    }

    public static SimulatorParameters fromProperties(Properties props, String sourceDescription) {
        SimulatorParameters.Builder builder = SimulatorParameters.builder();
        applyProperties(builder, toPlainMap(props), sourceDescription);
        return builder.build();
    }

    public static void applyProperties(SimulatorParameters.Builder builder, Map<String, String> values,
            String sourceDescription) {
        Map<String, String> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            String key = normalizeKey(entry.getKey());
            normalized.put(key, entry.getValue());
            applyProperty(builder, key, entry.getValue(), sourceDescription);
        }
        warnIfSizeKeysIgnoredByProgramsMode(normalized, sourceDescription);
        warnIfTotalResourcesSetExplicitly(normalized, sourceDescription);
    }

    private static void warnIfTotalResourcesSetExplicitly(Map<String, String> normalized,
            String sourceDescription) {
        if (normalized.containsKey("totalResources")) {
            System.err.println("WARN: totalResources from " + sourceDescription + " is ignored - it is now derived "
                    + "as instancePoolSize * sum(propertiesPerModel) from the actual constructed model pool "
                    + "(set propertiesPerModel/instancePoolSize instead)");
        }
    }

    private static void warnIfSizeKeysIgnoredByProgramsMode(Map<String, String> normalized,
            String sourceDescription) {
        String mode = normalized.get("transactionGenerationMode");
        if (mode == null || !"TRANSACTION_PROGRAMS".equalsIgnoreCase(mode.trim())) {
            return;
        }
        Set<String> ignoredKeysPresent = new LinkedHashSet<>();
        for (String sizeKey : SIZE_KEYS_IGNORED_BY_PROGRAMS_MODE) {
            if (normalized.containsKey(sizeKey)) {
                ignoredKeysPresent.add(sizeKey);
            }
        }
        if (!ignoredKeysPresent.isEmpty()) {
            System.err.println("WARN: transactionGenerationMode=TRANSACTION_PROGRAMS from " + sourceDescription
                    + " ignores the following keys also present in the same source: " + ignoredKeysPresent
                    + " (transaction size in TRANSACTION_PROGRAMS mode is determined by the chosen program's "
                    + "number of resource variables)");
        }
    }

    public static Properties loadResourceProperties(String filename) throws IOException {
        Properties props = new Properties();
        try (InputStream input = SimulatorParametersPropertyLoader.class.getClassLoader()
                .getResourceAsStream(filename)) {
            if (input == null) {
                throw new IOException("Unable to find config file: " + filename);
            }
            props.load(input);
        }
        return props;
    }

    public static Properties loadFileProperties(String filename) throws IOException {
        Properties props = new Properties();
        try (FileInputStream in = new FileInputStream(filename)) {
            props.load(in);
        }
        return props;
    }

    public static Map<String, String> extractEnvironmentOverrides(Map<String, String> environment) {
        Map<String, String> overrides = new LinkedHashMap<>();
        for (String supportedKey : SUPPORTED_KEYS) {
            String envVar = environmentVariableNameFor(supportedKey);
            String value = environment.get(envVar);
            if (value != null && !value.isBlank()) {
                overrides.put(supportedKey, value.trim());
            }
        }
        return overrides;
    }

    public static String environmentVariableNameFor(String key) {
        StringBuilder builder = new StringBuilder(ENVIRONMENT_PREFIX);
        for (int i = 0; i < key.length(); i++) {
            char current = key.charAt(i);
            if (Character.isUpperCase(current)) {
                builder.append('_');
            }
            builder.append(Character.toUpperCase(current));
        }
        return builder.toString();
    }

    private static Map<String, String> toPlainMap(Properties props) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String propertyName : props.stringPropertyNames()) {
            values.put(propertyName, props.getProperty(propertyName));
        }
        return values;
    }

    private static void applyProperty(SimulatorParameters.Builder builder, String key, String rawValue,
            String sourceDescription) {
        if (!SUPPORTED_KEYS.contains(key)) {
            throw new IllegalArgumentException(
                    "Unsupported simulator configuration key '" + key + "' from " + sourceDescription);
        }

        switch (key) {
            case "totalModels" -> builder.totalModels(parseInt(key, rawValue, sourceDescription));
            case "totalResources" -> builder.totalResources(parseInt(key, rawValue, sourceDescription));
            case "propertiesPerModel" -> builder.propertiesPerModel(parseIntList(key, rawValue, sourceDescription));
            case "instancePoolSize" -> builder.instancePoolSize(parseInt(key, rawValue, sourceDescription));
            case "ioTimePerResource" -> builder.ioTimePerResource(parseInt(key, rawValue, sourceDescription));
            case "cpuTimePerResource" -> builder.cpuTimePerResource(parseInt(key, rawValue, sourceDescription));
            case "lockIOTimePerResource" ->
                builder.lockIOTimePerResource(parseInt(key, rawValue, sourceDescription));
            case "lockCPUTimePerResource" ->
                builder.lockCPUTimePerResource(parseInt(key, rawValue, sourceDescription));
            case "initialTransactions" -> builder.initialTransactions(parseInt(key, rawValue, sourceDescription));
            case "totalTransactionSteps" -> builder.totalTransactionSteps(parseInt(key, rawValue, sourceDescription));
            case "maxTransactionModels" -> builder.maxTransactionModels(parseInt(key, rawValue, sourceDescription));
            case "maxTransactionResources" ->
                builder.maxTransactionResources(parseInt(key, rawValue, sourceDescription));
            case "useConstantTransactionSize" ->
                builder.useConstantTransactionSize(parseBoolean(key, rawValue, sourceDescription));
            case "constantTransactionSize" -> builder.constantTransactionSize(parseInt(key, rawValue, sourceDescription));
            case "transactionSizeProfile" ->
                builder.transactionSizeProfile(parseTransactionSizeProfile(key, rawValue, sourceDescription));
            case "smallTransactionSize" -> builder.smallTransactionSize(parseInt(key, rawValue, sourceDescription));
            case "largeTransactionSize" -> builder.largeTransactionSize(parseInt(key, rawValue, sourceDescription));
            case "largeTransactionProbability" ->
                builder.largeTransactionProbability(parseDouble(key, rawValue, sourceDescription));
            case "useRandomResourceSelection" ->
                builder.useRandomResourceSelection(parseBoolean(key, rawValue, sourceDescription));
            case "accessDistribution" ->
                builder.accessDistribution(parseAccessDistribution(key, rawValue, sourceDescription));
            case "zipfTheta" -> builder.zipfTheta(parseDouble(key, rawValue, sourceDescription));
            case "backoffScale" -> builder.backoffScale(parseDouble(key, rawValue, sourceDescription));
            case "workloadMix" -> builder.workloadMix(parseWorkloadMix(key, rawValue, sourceDescription));
            case "readOnlyTransactionFraction" ->
                builder.readOnlyTransactionFraction(parseDouble(key, rawValue, sourceDescription));
            case "semanticOverlap" -> builder.semanticOverlap(parseDouble(key, rawValue, sourceDescription));
            case "maxCprsPerResource" -> builder.maxCprsPerResource(parseInt(key, rawValue, sourceDescription));
            case "avgCprsPerResource" -> builder.avgCprsPerResource(parseDouble(key, rawValue, sourceDescription));
            case "cprTopologyMode" -> builder.cprTopologyMode(parseCprTopologyMode(key, rawValue, sourceDescription));
            case "referentialAction" -> builder.referentialAction(parseReferentialAction(key, rawValue, sourceDescription));
            case "concurrencyModel" -> builder.concurrencyModel(parseConcurrencyModel(key, rawValue, sourceDescription));
            case "simulationDurationSeconds" ->
                builder.simulationDurationSeconds(parseInt(key, rawValue, sourceDescription));
            case "numberOfIoProcessors" -> builder.numberOfIoProcessors(parseInt(key, rawValue, sourceDescription));
            case "numberOfCpuProcessors" -> builder.numberOfCpuProcessors(parseInt(key, rawValue, sourceDescription));
            case "numberOfReconcilerProcessors" ->
                builder.numberOfReconcilerProcessors(parseInt(key, rawValue, sourceDescription));
            case "totalLocks" -> builder.totalLocks(parseInt(key, rawValue, sourceDescription));
            case "transactionSamplingSeed" ->
                builder.transactionSamplingSeed(parseLongObject(key, rawValue, sourceDescription));
            case "semanticOverlapSeed" -> builder.semanticOverlapSeed(parseLongObject(key, rawValue, sourceDescription));
            case "stepTimeSlice" -> builder.stepTimeSlice(parseLong(key, rawValue, sourceDescription));
            case "transactionMode" -> builder.transactionMode(parseTransactionMode(key, rawValue, sourceDescription));
            case "numTerminals" -> builder.numTerminals(parseInt(key, rawValue, sourceDescription));
            case "meanInterArrivalDelay" -> builder.meanInterArrivalDelay(parseLong(key, rawValue, sourceDescription));
            case "transactionGenerationMode" ->
                builder.transactionGenerationMode(parseTransactionGenerationMode(key, rawValue, sourceDescription));
            default -> throw new IllegalArgumentException(
                    "Unsupported simulator configuration key '" + key + "' from " + sourceDescription);
        }
    }

    private static String normalizeKey(String key) {
        if (key.startsWith(RESOURCE_PREFIX)) {
            return key.substring(RESOURCE_PREFIX.length());
        }
        return key;
    }

    private static int parseInt(String key, String rawValue, String sourceDescription) {
        try {
            return Integer.parseInt(rawValue.trim());
        } catch (NumberFormatException e) {
            throw invalidValue("integer", key, rawValue, sourceDescription, e);
        }
    }

    private static List<Integer> parseIntList(String key, String rawValue, String sourceDescription) {
        String trimmed = rawValue.trim();
        if (trimmed.isEmpty()) {
            throw invalidValue("comma-separated integer list", key, rawValue, sourceDescription, null);
        }
        List<Integer> values = new ArrayList<>();
        for (String part : trimmed.split(",")) {
            values.add(parseInt(key, part, sourceDescription));
        }
        return values;
    }

    private static long parseLong(String key, String rawValue, String sourceDescription) {
        try {
            return Long.parseLong(rawValue.trim());
        } catch (NumberFormatException e) {
            throw invalidValue("long", key, rawValue, sourceDescription, e);
        }
    }

    private static Long parseLongObject(String key, String rawValue, String sourceDescription) {
        if (rawValue == null || rawValue.trim().isEmpty()) {
            return null;
        }
        return parseLong(key, rawValue, sourceDescription);
    }

    private static double parseDouble(String key, String rawValue, String sourceDescription) {
        try {
            return Double.parseDouble(rawValue.trim());
        } catch (NumberFormatException e) {
            throw invalidValue("decimal", key, rawValue, sourceDescription, e);
        }
    }

    private static boolean parseBoolean(String key, String rawValue, String sourceDescription) {
        String normalized = rawValue.trim().toLowerCase();
        if ("true".equals(normalized)) {
            return true;
        }
        if ("false".equals(normalized)) {
            return false;
        }
        throw invalidValue("boolean", key, rawValue, sourceDescription, null);
    }

    private static ReferentialAction parseReferentialAction(String key, String rawValue,
            String sourceDescription) {
        try {
            return ReferentialAction.valueOf(rawValue.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw invalidEnumValue(key, rawValue, sourceDescription,
                    Arrays.toString(ReferentialAction.values()), e);
        }
    }

    private static ConcurrencyModel parseConcurrencyModel(String key, String rawValue,
            String sourceDescription) {
        try {
            return ConcurrencyModel.valueOf(rawValue.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw invalidEnumValue(key, rawValue, sourceDescription,
                    Arrays.toString(ConcurrencyModel.values()), e);
        }
    }

    private static TransactionMode parseTransactionMode(String key, String rawValue, String sourceDescription) {
        try {
            return TransactionMode.valueOf(rawValue.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw invalidEnumValue(key, rawValue, sourceDescription, Arrays.toString(TransactionMode.values()), e);
        }
    }

    private static TransactionSizeProfile parseTransactionSizeProfile(String key, String rawValue,
            String sourceDescription) {
        try {
            return TransactionSizeProfile.valueOf(rawValue.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw invalidEnumValue(key, rawValue, sourceDescription,
                    Arrays.toString(TransactionSizeProfile.values()), e);
        }
    }

    private static TransactionGenerationMode parseTransactionGenerationMode(String key, String rawValue,
            String sourceDescription) {
        try {
            return TransactionGenerationMode.valueOf(rawValue.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw invalidEnumValue(key, rawValue, sourceDescription,
                    Arrays.toString(TransactionGenerationMode.values()), e);
        }
    }

    private static ResourceAccessDistribution parseAccessDistribution(String key, String rawValue,
            String sourceDescription) {
        try {
            return ResourceAccessDistribution.valueOf(rawValue.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw invalidEnumValue(key, rawValue, sourceDescription,
                    Arrays.toString(ResourceAccessDistribution.values()), e);
        }
    }

    private static WorkloadMix parseWorkloadMix(String key, String rawValue, String sourceDescription) {
        try {
            return WorkloadMix.valueOf(rawValue.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw invalidEnumValue(key, rawValue, sourceDescription, Arrays.toString(WorkloadMix.values()), e);
        }
    }

    private static CprTopologyMode parseCprTopologyMode(String key, String rawValue, String sourceDescription) {
        try {
            return CprTopologyMode.valueOf(rawValue.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw invalidEnumValue(key, rawValue, sourceDescription, Arrays.toString(CprTopologyMode.values()), e);
        }
    }

    private static IllegalArgumentException invalidValue(String expectedType, String key, String rawValue,
            String sourceDescription, Exception cause) {
        return new IllegalArgumentException(
                "Invalid " + expectedType + " value for key '" + key + "' from " + sourceDescription + ": " + rawValue,
                cause);
    }

    private static IllegalArgumentException invalidEnumValue(String key, String rawValue, String sourceDescription,
            String expectedValues, Exception cause) {
        return new IllegalArgumentException(
                "Invalid enum value for key '" + key + "' from " + sourceDescription + ": " + rawValue
                        + " (expected one of " + expectedValues + ")",
                cause);
    }
}
