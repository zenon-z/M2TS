package de.tum.tssimulator.cli;

import de.tum.tssimulator.bootstrap.SimulationWatchdogException;
import de.tum.tssimulator.bootstrap.SimulatorConfigurationException;

public enum StartupExitCode {
    SUCCESS(0),
    CONFIG_ERROR(2),
    RUNTIME_ERROR(3),
    WATCHDOG_FAILURE(4);

    private final int code;

    StartupExitCode(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    public static StartupExitCode fromFailure(Throwable failure) {
        if (failure instanceof SimulatorConfigurationException) {
            return CONFIG_ERROR;
        }
        if (failure instanceof SimulationWatchdogException) {
            return WATCHDOG_FAILURE;
        }
        return RUNTIME_ERROR;
    }
}
