package de.tum.tssimulator.monitoring;

import java.io.PrintStream;

class StdoutSimulationEventSink implements SimulationEventSink {
    private final PrintStream stream;

    public StdoutSimulationEventSink() {
        this(System.out);
    }

    StdoutSimulationEventSink(PrintStream stream) {
        this.stream = stream;
    }

    @Override
    public void writeLine(String line) {
        stream.println(line);
    }

    @Override
    public void close() {
        stream.flush();
    }
}
