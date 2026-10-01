package test.java.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import org.junit.jupiter.api.Test;

import de.tum.tssimulator.bookkeeping.CprTopologyMode;
import de.tum.tssimulator.config.SimulatorParameters;
import de.tum.tssimulator.config.SimulatorParametersPropertyLoader;

class SimulatorParametersPropertyLoaderTest {

    @Test
    void invalidIntegerPropertyFailsFast() throws IOException {
        Path propsFile = Files.createTempFile("simulator-invalid-int", ".properties");
        Files.writeString(propsFile, """
                totalModels=abc
                totalResources=5000
                """);

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> SimulatorParametersPropertyLoader.fromPropertiesFile(propsFile.toString()));

        assertTrue(ex.getMessage().contains("totalModels"),
                "Failure should identify the invalid property");
    }

    @Test
    void invalidEnumPropertyFailsFast() throws IOException {
        Path propsFile = Files.createTempFile("simulator-invalid-enum", ".properties");
        Files.writeString(propsFile, """
                concurrencyModel=eventual-ish
                totalModels=1
                totalResources=5000
                """);

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> SimulatorParametersPropertyLoader.fromPropertiesFile(propsFile.toString()));

        assertTrue(ex.getMessage().contains("concurrencyModel"),
                "Failure should identify the invalid enum property");
    }

    @Test
    void invalidBooleanPropertyFailsFast() throws IOException {
        Path propsFile = Files.createTempFile("simulator-invalid-bool", ".properties");
        Files.writeString(propsFile, "useConstantTransactionSize=maybe");

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> SimulatorParametersPropertyLoader.fromPropertiesFile(propsFile.toString()));

        assertTrue(ex.getMessage().contains("useConstantTransactionSize"));
        assertTrue(ex.getMessage().contains("boolean"));
    }

    @Test
    void invalidLongPropertyFailsFast() throws IOException {
        Path propsFile = Files.createTempFile("simulator-invalid-long", ".properties");
        Files.writeString(propsFile, "stepTimeSlice=one-thousand");

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> SimulatorParametersPropertyLoader.fromPropertiesFile(propsFile.toString()));

        assertTrue(ex.getMessage().contains("stepTimeSlice"));
        assertTrue(ex.getMessage().contains("long"));
    }

    @Test
    void validationErrorsIncludeSourceAndKey() throws IOException {
        Path propsFile = Files.createTempFile("simulator-invalid-boundary", ".properties");
        Files.writeString(propsFile, "totalModels=-1");

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> SimulatorParametersPropertyLoader.fromPropertiesFile(propsFile.toString()));

        assertTrue(ex.getMessage().contains("totalModels"));
    }

    @Test
    void supportsPrefixedAndUnprefixedPropertyKeys() throws IOException {
        Properties props = new Properties();
        props.setProperty("simulator.totalModels", "2");
        props.setProperty("totalResources", "20");
        props.setProperty("simulator.numberOfIoProcessors", "3");
        props.setProperty("maxCprsPerResource", "4");
        props.setProperty("simulator.avgCprsPerResource", "2.5");

        SimulatorParameters params = SimulatorParametersPropertyLoader.fromProperties(props, "test properties");

        assertEquals(2, params.totalModels);
        assertEquals(20, params.totalResources);
        assertEquals(3, params.numberOfIoProcessors);
        assertEquals(4, params.maxCprsPerResource);
        assertEquals(2.5, params.avgCprsPerResource);
    }

    @Test
    void cprFanOutValidationRejectsInvalidAverage() throws IOException {
        Path propsFile = Files.createTempFile("simulator-invalid-cpr-average", ".properties");
        Files.writeString(propsFile, """
                maxCprsPerResource=2
                avgCprsPerResource=3.0
                """);

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> SimulatorParametersPropertyLoader.fromPropertiesFile(propsFile.toString()));

        assertTrue(ex.getMessage().contains("avgCprsPerResource"));
    }

    @Test
    void parsesCprTopologyMode() {
        Properties props = new Properties();
        props.setProperty("cprTopologyMode", "ring");

        SimulatorParameters params = SimulatorParametersPropertyLoader.fromProperties(props, "test properties");

        assertEquals(CprTopologyMode.RING, params.cprTopologyMode);
    }

    @Test
    void warnsWhenSizeKeysCombinedWithTransactionProgramsMode() {
        Properties props = new Properties();
        props.setProperty("transactionGenerationMode", "TRANSACTION_PROGRAMS");
        props.setProperty("transactionSizeProfile", "BIMODAL");
        props.setProperty("maxTransactionResources", "10");
        props.setProperty("useConstantTransactionSize", "true");

        PrintStream originalErr = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setErr(new PrintStream(captured));
        try {
            SimulatorParametersPropertyLoader.fromProperties(props, "test properties");
        } finally {
            System.setErr(originalErr);
        }

        String output = captured.toString();
        assertTrue(output.contains("WARN"));
        assertTrue(output.contains("transactionSizeProfile"));
        assertTrue(output.contains("maxTransactionResources"));
        assertTrue(output.contains("useConstantTransactionSize"));
    }

    @Test
    void noWarnWhenTransactionProgramsModeWithoutSizeKeys() {
        Properties props = new Properties();
        props.setProperty("transactionGenerationMode", "TRANSACTION_PROGRAMS");

        PrintStream originalErr = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setErr(new PrintStream(captured));
        try {
            SimulatorParametersPropertyLoader.fromProperties(props, "test properties");
        } finally {
            System.setErr(originalErr);
        }

        assertFalse(captured.toString().contains("WARN"));
    }

    @Test
    void noWarnWhenSizeKeysPresentButModeIsRandomOperations() {
        Properties props = new Properties();
        props.setProperty("transactionGenerationMode", "RANDOM_OPERATIONS");
        props.setProperty("transactionSizeProfile", "BIMODAL");

        PrintStream originalErr = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setErr(new PrintStream(captured));
        try {
            SimulatorParametersPropertyLoader.fromProperties(props, "test properties");
        } finally {
            System.setErr(originalErr);
        }

        assertFalse(captured.toString().contains("WARN"));
    }
}
