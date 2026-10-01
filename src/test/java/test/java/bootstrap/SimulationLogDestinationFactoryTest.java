package test.java.bootstrap;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import de.tum.tssimulator.bootstrap.SimulationLogDestinationFactory;
import de.tum.tssimulator.monitoring.SimulationEventSink;

class SimulationLogDestinationFactoryTest {
    private static final String LOG_PATH_PROPERTY = "ts.simulator.log.path";
    private static final String LOG_STDOUT_ONLY_PROPERTY = "ts.simulator.log.stdoutOnly";

    @AfterEach
    void tearDown() {
        System.clearProperty(LOG_PATH_PROPERTY);
        System.clearProperty(LOG_STDOUT_ONLY_PROPERTY);
    }

    @Test
    void writesToConfiguredLogFilePath() throws Exception {
        Path logFile = Files.createTempDirectory("ts-simulator-log-test")
                .resolve("custom")
                .resolve("simulation.log");
        System.setProperty(LOG_PATH_PROPERTY, logFile.toString());

        SimulationEventSink sink = SimulationLogDestinationFactory.create();
        sink.writeLine("hello-file-sink");
        sink.close();

        assertTrue(Files.exists(logFile), "Configured log path should be created");
        assertTrue(Files.readString(logFile).contains("hello-file-sink"));
    }

    @Test
    void supportsStdoutOnlyMode() {
        PrintStream originalOut = System.out;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(output, true, StandardCharsets.UTF_8));
            System.setProperty(LOG_STDOUT_ONLY_PROPERTY, "true");

            SimulationEventSink sink = SimulationLogDestinationFactory.create();
            sink.writeLine("hello-stdout-sink");
            sink.close();
        } finally {
            System.setOut(originalOut);
        }

        String written = output.toString(StandardCharsets.UTF_8);
        assertTrue(written.contains("hello-stdout-sink"));
        assertTrue(written.contains("hello-stdout-sink"));
    }
}
