package de.tum.tssimulator.bootstrap;

import java.nio.file.Path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.tum.tssimulator.monitoring.SimulationEventSink;
import de.tum.tssimulator.monitoring.SimulationEventSinkFactory;

public final class SimulationLogDestinationFactory {
    private static final Logger LOGGER = LoggerFactory.getLogger(SimulationLogDestinationFactory.class);
    static final String LOG_PATH_PROPERTY = "ts.simulator.log.path";
    static final String LOG_STDOUT_ONLY_PROPERTY = "ts.simulator.log.stdoutOnly";
    // Experiment/seed sweeps construct a fresh SimulatorContext (and thus a fresh
    // SimulationLogger/sink) per individual simulation run - see SimulatorContext.create(). The
    // default file sink logs one line per resource release plus every phase/lifecycle event,
    // which at high mpl/throughput runs to ~1GB of disk I/O for a SINGLE seed's run - pure wasted
    // wall-clock cost for a sweep, whose pipeline only ever reads the aggregated CSV output, never
    // this per-event log. Checked before LOG_STDOUT_ONLY_PROPERTY since discarding is strictly
    // "less" than stdout-only (redirecting the same volume to the terminal is no cheaper).
    static final String LOG_DISCARD_PROPERTY = "ts.simulator.log.discard";
    private static final Path DEFAULT_LOG_PATH = Path.of("logs", "simulation.log");

    private SimulationLogDestinationFactory() {
    }

    public static SimulationEventSink create() {
        if (Boolean.getBoolean(LOG_DISCARD_PROPERTY)) {
            LOGGER.debug("Simulation event sink configured to discard via {}", LOG_DISCARD_PROPERTY);
            return SimulationEventSinkFactory.discard();
        }
        if (Boolean.getBoolean(LOG_STDOUT_ONLY_PROPERTY)) {
            LOGGER.debug("Simulation event sink configured for stdout only via {}", LOG_STDOUT_ONLY_PROPERTY);
            return SimulationEventSinkFactory.stdout();
        }

        String configuredPath = System.getProperty(LOG_PATH_PROPERTY);
        Path logPath = configuredPath == null || configuredPath.isBlank()
                ? DEFAULT_LOG_PATH
                : Path.of(configuredPath);
        LOGGER.debug("Simulation event sink configured for file output at {}", logPath);
        return SimulationEventSinkFactory.file(logPath);
    }

    public static Path defaultLogPath() {
        return DEFAULT_LOG_PATH;
    }
}
