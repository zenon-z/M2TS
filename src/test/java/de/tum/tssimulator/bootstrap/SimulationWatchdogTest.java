package de.tum.tssimulator.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SimulationWatchdogTest {

    @Test
    void resetsWhenProgressResumes() {
        SimulationWatchdog watchdog = new SimulationWatchdog(2);

        assertNull(watchdog.observe(10L, 0, 0, this::diagnostics));
        assertNull(watchdog.observe(20L, 1, 0, this::diagnostics));
        assertNull(watchdog.observe(30L, 1, 0, this::diagnostics));
    }

    @Test
    void returnsDiagnosticsWhenNoProgressPersists() {
        SimulationWatchdog watchdog = new SimulationWatchdog(2);

        assertNull(watchdog.observe(10L, 0, 0, this::diagnostics));
        SimulationWatchdogDiagnostics diagnostics = watchdog.observe(20L, 0, 0, this::diagnostics);

        assertNotNull(diagnostics);
        assertEquals("CLASSIC_DEADLOCK", diagnostics.classification());
        assertTrue(diagnostics.structuredLogLine().contains("\"event\":\"WATCHDOG_FAILURE\""));
    }

    private SimulationWatchdogDiagnostics diagnostics() {
        return new SimulationWatchdogDiagnostics(20L, 2, 1, 0, 1, 0, 0, 0, 1, 1, 0, 0, 0, 0, 0, 0,
                "CLASSIC_DEADLOCK", "blocked with no running progress");
    }
}
