package test.java.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import org.junit.jupiter.api.Test;

import de.tum.tssimulator.cli.StartupExitCode;

class PackagedJarSmokeIT {

    @Test
    void packagedJarStartsAndCompletesWithBundledDefaultConfig() throws Exception {
        Path jarPath = packagedJar();

        Process process = new ProcessBuilder(
                javaExecutable(),
                "-jar",
                jarPath.toString())
                .redirectErrorStream(true)
                .start();

        boolean finished = process.waitFor(Duration.ofSeconds(60).toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
        assertTrue(finished, "Packaged JAR should complete with bundled default config");

        String output = readAll(process.getInputStream());
        assertEquals(0, process.exitValue(), () -> "Packaged JAR should exit successfully with default config. Output:\n" + output);
        assertTrue(output.contains("Simulation completed"),
                () -> "Packaged JAR output should confirm completion with default config. Output:\n" + output);
    }

    @Test
    void packagedJarStartsAndCompletesWithExplicitConfig() throws Exception {
        Path jarPath = packagedJar();

        Path configFile = Files.createTempFile("ts-simulator-smoke", ".properties");
        Files.writeString(configFile, """
                totalModels=1
                totalResources=25
                ioTimePerResource=1
                cpuTimePerResource=1
                lockIOTimePerResource=0
                lockCPUTimePerResource=0
                initialTransactions=1
                totalTransactionSteps=5
                maxTransactionModels=1
                maxTransactionResources=2
                simulationDurationSeconds=1
                numberOfIoProcessors=1
                numberOfCpuProcessors=1
                totalLocks=25
                stepTimeSlice=1000
                """);

        Process process = new ProcessBuilder(
                javaExecutable(),
                "-jar",
                jarPath.toString(),
                configFile.toString())
                .redirectErrorStream(true)
                .start();

        boolean finished = process.waitFor(Duration.ofSeconds(30).toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
        assertTrue(finished, "Packaged JAR should complete within timeout");

        String output = readAll(process.getInputStream());
        assertEquals(0, process.exitValue(), () -> "Packaged JAR should exit successfully. Output:\n" + output);
        assertTrue(output.contains("Simulation completed"),
                () -> "Packaged JAR output should confirm completion. Output:\n" + output);
    }

    @Test
    void packagedJarFailsFastWithInvalidConfig() throws Exception {
        Path jarPath = packagedJar();

        Path configFile = Files.createTempFile("ts-simulator-invalid", ".properties");
        Files.writeString(configFile, """
                totalModels=not-a-number
                totalResources=25
                """);

        Process process = new ProcessBuilder(
                javaExecutable(),
                "-jar",
                jarPath.toString(),
                configFile.toString())
                .redirectErrorStream(true)
                .start();

        boolean finished = process.waitFor(Duration.ofSeconds(30).toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
        assertTrue(finished, "Packaged JAR should fail fast on invalid config");

        String output = readAll(process.getInputStream());
        assertNotEquals(0, process.exitValue(), "Packaged JAR should exit non-zero on invalid config");
        assertEquals(StartupExitCode.CONFIG_ERROR.code(), process.exitValue(),
                () -> "Packaged JAR should use the documented config-failure exit code. Output:\n" + output);
        assertTrue(output.contains("Failed to load simulator configuration"),
                () -> "Packaged JAR output should explain configuration failure. Output:\n" + output);
    }

    private static Path packagedJar() {
        Path jarPath = Path.of("target", "ts-simulator-1.0.0-SNAPSHOT.jar");
        assertTrue(Files.exists(jarPath), "Packaged JAR should exist before integration smoke test runs");
        return jarPath;
    }

    private static String javaExecutable() {
        Path javaHome = Path.of(System.getProperty("java.home"));
        return javaHome.resolve("bin").resolve("java").toString();
    }

    private static String readAll(InputStream inputStream) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        inputStream.transferTo(buffer);
        return buffer.toString(StandardCharsets.UTF_8);
    }
}
