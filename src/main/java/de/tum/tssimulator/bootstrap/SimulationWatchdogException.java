package de.tum.tssimulator.bootstrap;

public class SimulationWatchdogException extends RuntimeException {
    private final String structuredDiagnostics;

    public SimulationWatchdogException(String message) {
        this(message, null);
    }

    public SimulationWatchdogException(String message, String structuredDiagnostics) {
        super(message);
        this.structuredDiagnostics = structuredDiagnostics;
    }

    public String getStructuredDiagnostics() {
        return structuredDiagnostics;
    }
}
