package de.tum.tssimulator.monitoring;

import java.nio.file.Path;

public final class SimulationEventSinkFactory {
    private SimulationEventSinkFactory() {
    }

    public static SimulationEventSink stdout() {
        return new StdoutSimulationEventSink();
    }

    public static SimulationEventSink discard() {
        return new NullSimulationEventSink();
    }

    public static SimulationEventSink file(Path path) {
        return new PrintWriterSimulationEventSink(path);
    }
}
